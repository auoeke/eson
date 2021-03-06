package net.auoeke.eson.parser.lexer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import net.auoeke.eson.parser.Context;
import net.auoeke.eson.parser.lexer.error.ErrorKey;
import net.auoeke.eson.parser.lexer.error.SyntaxError;
import net.auoeke.eson.parser.lexer.error.SyntaxException;
import net.auoeke.eson.parser.lexer.lexeme.CommaLexeme;
import net.auoeke.eson.parser.lexer.lexeme.CommentLexeme;
import net.auoeke.eson.parser.lexer.lexeme.DelimiterLexeme;
import net.auoeke.eson.parser.lexer.lexeme.Lexeme;
import net.auoeke.eson.parser.lexer.lexeme.MappingLexeme;
import net.auoeke.eson.parser.lexer.lexeme.NewlineLexeme;
import net.auoeke.eson.parser.lexer.lexeme.StringLexeme;
import net.auoeke.eson.parser.lexer.lexeme.Token;
import net.auoeke.eson.parser.lexer.lexeme.WhitespaceLexeme;
import net.auoeke.eson.Eson;

public class Lexer {
    private static final String STRING_DELIMITERS = "\"'`";
    private static final String LINE_COMMENT = "##";
    private static final String BLOCK_COMMENT = "/*";
    private static final String BLOCK_COMMENT_END = "*/";

    private final String eson;
    private final List<Lexeme> lexemes = new ArrayList<>();
    private final boolean retainComments;
    private final boolean retainSource;
    private final boolean throwExceptions;

    private int nextIndex;
    private int line = 1;
    private int column = 0;
    private int savedLine = -1;
    private int savedColumn = -1;
    private char current;
    private Context context;
    private Lexeme lastCode;

    public Lexer(String eson, boolean retainSource, boolean throwExceptions, Eson.Option... options) {
        this.eson = eson;

        var optionSet = new HashSet<>(Arrays.asList(options));
        this.retainComments = optionSet.contains(Eson.Option.RETAIN_COMMENTS);
        this.retainSource = retainSource;
        this.throwExceptions = throwExceptions;

        while (this.advance()) {
            this.process();
        }
    }

    public Lexer(String eson, Eson.Option... options) {
        this(eson, false, true);
    }

    public LexemeIterator iterator() {
        return new LexemeIterator(this.lexemes.size(), this.lexemes.toArray(Lexeme[]::new));
    }

    private String position() {
        return this.savedLine + ":" + this.savedColumn;
    }

    private int previousIndex() {
        return this.nextIndex - 1;
    }

    private char next() {
        this.current = this.eson.charAt(this.nextIndex++);

        if (this.current == '\n') {
            ++this.line;
            this.column = 0;
        }

        ++this.column;

        return this.current;
    }

    private boolean advance() {
        if (this.nextIndex < this.eson.length()) {
            this.next();

            return true;
        }

        return false;
    }

    private char previous() {
        var previous = this.eson.charAt(--this.nextIndex);

        if (previous == '\n') {
            this.line--;
        }

        this.column--;

        return previous;
    }

    private boolean peek(String expected) {
        return this.eson.regionMatches(this.previousIndex(), expected, 0, expected.length());
    }

    private void index(int index) {
        this.nextIndex = index + 1;
        this.current = this.eson.charAt(index);
    }

    private void savePosition() {
        this.savedLine = this.line;
        this.savedColumn = this.column;
    }

    private boolean whitespaceOrComment() {
        if (this.current == '\n') {
            this.add(new NewlineLexeme(this.savedLine, this.savedColumn));
        } else if (Character.isWhitespace(this.current)) {
            this.whitespace(this.current);
        } else {
            return this.comment();
        }

        return true;
    }

    private void requireClose(Token character, ErrorKey error) {
        var position = this.position();

        while (this.advance()) {
            if (this.current == character.character()) {
                this.structure(character.character());
                return;
            }

            this.process();
        }

        this.error(position, error);
    }

    private boolean comma(char character) {
        if (character == ',') {
            this.add(new CommaLexeme(this.savedLine, this.savedColumn));
            return true;
        }

        return false;
    }

    private boolean mapping(char character) {
        if (character == '=') {
            this.add(new MappingLexeme(this.savedLine, this.savedColumn));
            return true;
        }

        return false;
    }

    private void scanExpression() {
        if (contains("{}[]", this.current)) {
            this.structure(this.current);
        } else {
            if (contains(STRING_DELIMITERS, this.current)) {
                this.string(this.current);
            } else {
                this.rawString(this.current);
            }
        }
    }

    private void process() {
        this.savePosition();

        if (!this.whitespaceOrComment() && !this.comma(this.current) && !this.mapping(this.current)) {
            this.scanExpression();

            while (this.advance()) {
                if (this.shouldTerminateExpression()) {
                    this.previous();
                    return;
                }

                if (Character.isWhitespace(this.current)) {
                    this.whitespace(this.current);
                } else {
                    this.error(ErrorKey.NO_SEPARATOR);
                }
            }
        }
    }

    private void add(Lexeme lexeme) {
        if (lexeme != null && (lexeme.token().comment() ? this.retainComments : this.retainSource || lexeme.token() != Token.WHITESPACE)) {
            this.lexemes.add(lexeme);

            if (!lexeme.token().sourceOnly()) {
                this.lastCode = lexeme;
            }
        }
    }

    private void error(String position, ErrorKey error) {
        if (this.throwExceptions) {
            throw new SyntaxException(position, error);
        }

        if (this.lastCode != null) {
            this.lastCode.error = new SyntaxError(error.template.formatted(position));
        }
    }

    private void error(ErrorKey error) {
        this.error(this.position(), error);
    }

    private void whitespace(char whitespace) {
        this.add(new WhitespaceLexeme(this.savedLine, this.savedColumn, buildString(builder -> {
            builder.append(whitespace);

            while (this.advance()) {
                if (!Character.isWhitespace(this.current) || this.current == '\n') {
                    this.previous();
                    return;
                }

                builder.append(this.current);
            }
        })));
    }

    private boolean comment() {
        if (this.peek(BLOCK_COMMENT)) {
            this.add(new CommentLexeme(this.savedLine, this.savedColumn, Token.BLOCK_COMMENT, buildString(builder -> {
                var depth = 1;

                while (this.advance()) {
                    if (this.peek(BLOCK_COMMENT)) {
                        ++this.nextIndex;
                        ++depth;
                        builder.append(BLOCK_COMMENT);
                    } else if (this.peek(BLOCK_COMMENT_END)) {
                        ++this.nextIndex;

                        if (--depth == 0) {
                            return;
                        }

                        builder.append(BLOCK_COMMENT_END);
                    } else {
                        builder.append(this.current);
                    }
                }
            })));
        } else if (this.peek(LINE_COMMENT)) {
            this.add(new CommentLexeme(this.savedLine, this.savedColumn, Token.LINE_COMMENT, buildString(builder -> {
                while (this.advance()) {
                    if (this.current == '\n') {
                        this.previous();
                        return;
                    }

                    builder.append(this.current);
                }
            })));
        } else {
            return false;
        }
        return true;
    }

    private void structure(char character) {
        var token = Token.delimiter(character);
        this.add(new DelimiterLexeme(this.savedLine, this.savedColumn, token));

        switch (token) {
            case ARRAY_END -> {
                if (this.context != Context.ARRAY) {
                    this.error(ErrorKey.RBRACKET_OUTSIDE_ARRAY);
                }
            }
            case MAP_END -> {
                if (this.context != Context.MAP) {
                    this.error(ErrorKey.RBRACE_OUTSIDE_MAP);
                }
            }
            default -> {
                var previousContext = this.context;

                switch (token) {
                    case ARRAY_BEGIN -> {
                        this.context = Context.ARRAY;
                        this.requireClose(Token.ARRAY_END, ErrorKey.UNCLOSED_ARRAY);
                    }
                    case MAP_BEGIN -> {
                        this.context = Context.MAP;
                        this.requireClose(Token.MAP_END, ErrorKey.UNCLOSED_MAP);
                    }
                }

                this.context = previousContext;
            }
        }
    }

    private int delimiterLength(char delimiter) {
        var length = 1;

        while (this.advance()) {
            if (this.current != delimiter) {
                this.previous();
                return length;
            }

            ++length;
        }

        return length;
    }

    private void string(char delimiter) {
        var length = this.delimiterLength(delimiter);

        if (length == 2) {
            this.add(new StringLexeme(this.savedLine, this.savedColumn, String.valueOf(delimiter), ""));
        } else {
            this.add(new StringLexeme(this.savedLine, this.savedColumn, String.valueOf(delimiter).repeat(length), buildString(builder -> {
                while (this.advance()) {
                    if (this.current == delimiter) {
                        var count = 1;

                        while (count < length && this.next() == delimiter) {
                            ++count;
                        }

                        if (count == length) {
                            return;
                        }

                        this.index(this.previousIndex() - count);
                        builder.append(this.eson, this.previousIndex(), this.nextIndex += count);
                    } else {
                        builder.append(this.current);
                    }
                }

                this.error(ErrorKey.UNCLOSED_STRING);
            })));
        }
    }

    private void rawString(char character) {
        var additionalLexemes = new ArrayList<Lexeme>();

        var string = buildString(builder -> {
            builder.append(character);
            var whitespace = -1;

            while (this.advance()) {
                if (this.shouldTerminateExpression()) {
                    if (whitespace != -1) {
                        additionalLexemes.add(new WhitespaceLexeme(this.savedLine, this.savedColumn, this.eson.substring(whitespace, this.previousIndex())));
                    }

                    switch (character) {
                        case '=' -> additionalLexemes.add(new MappingLexeme(this.savedLine, this.savedColumn));
                        default -> this.previous();
                    }

                    return;
                }

                if (Character.isWhitespace(this.current)) {
                    if (whitespace == -1) {
                        whitespace = this.previousIndex();
                    }
                } else if (whitespace == -1) {
                    builder.append(this.current);
                } else {
                    builder.append(this.eson, whitespace, this.nextIndex);
                    whitespace = -1;
                }
            }
        });

        this.add(new StringLexeme(this.savedLine, this.savedColumn, null, string));

        additionalLexemes.forEach(this::add);
    }

    private boolean shouldTerminateExpression() {
        return contains("\n,={}[]", this.current) || this.peek(LINE_COMMENT) || this.peek(BLOCK_COMMENT);
    }

    private static String buildString(Consumer<StringBuilder> builder) {
        var b = new StringBuilder();
        builder.accept(b);
        return b.toString();
    }

    private static boolean contains(String string, int character) {
        return string.indexOf(character) >= 0;
    }
}
