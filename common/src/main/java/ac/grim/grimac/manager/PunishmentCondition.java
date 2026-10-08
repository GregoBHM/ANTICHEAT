package ac.grim.grimac.manager;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PunishmentCondition {
    private static final Pattern MATRIX_PLACEHOLDER =
            Pattern.compile("-([A-Za-z0-9_:.%]+)-");

    interface ValueResolver {
        double resolve(String name);
    }

    private final Node root;

    private PunishmentCondition(Node root) {
        this.root = root;
    }

    static PunishmentCondition parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Condition cannot be empty");
        }

        String normalized = normalizeMatrixPlaceholders(expression);
        Parser parser = new Parser(normalized);
        Node root = parser.parseExpression();
        parser.skipWhitespace();

        if (!parser.atEnd()) {
            throw new IllegalArgumentException(
                    "Unexpected token at position " + parser.position()
                            + " in condition '" + expression + "'"
            );
        }

        return new PunishmentCondition(root);
    }

    boolean test(ValueResolver resolver) {
        try {
            return root.test(resolver);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String normalizeMatrixPlaceholders(String input) {
        Matcher matcher = MATRIX_PLACEHOLDER.matcher(input);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            matcher.group(1).toLowerCase(Locale.ROOT)
                    )
            );
        }

        matcher.appendTail(out);
        return out.toString();
    }

    private interface Node {
        boolean test(ValueResolver resolver);
    }

    private record BinaryNode(
            Node left,
            Node right,
            boolean and
    ) implements Node {
        @Override
        public boolean test(ValueResolver resolver) {
            return and
                    ? left.test(resolver) && right.test(resolver)
                    : left.test(resolver) || right.test(resolver);
        }
    }

    private record NotNode(Node child) implements Node {
        @Override
        public boolean test(ValueResolver resolver) {
            return !child.test(resolver);
        }
    }

    private record ComparisonNode(
            Operand left,
            Operand right,
            Comparator comparator
    ) implements Node {
        @Override
        public boolean test(ValueResolver resolver) {
            double a = left.value(resolver);
            double b = right.value(resolver);

            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                return false;
            }

            return switch (comparator) {
                case GT -> a > b;
                case GTE -> a >= b;
                case LT -> a < b;
                case LTE -> a <= b;
                case EQ -> Double.compare(a, b) == 0;
                case NEQ -> Double.compare(a, b) != 0;
            };
        }
    }

    private record Operand(
            Double literal,
            String variable
    ) {
        double value(ValueResolver resolver) {
            if (literal != null) {
                return literal;
            }

            return resolver.resolve(variable);
        }
    }

    private enum Comparator {
        GT,
        GTE,
        LT,
        LTE,
        EQ,
        NEQ
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        Node parseExpression() {
            Node node = parseTerm();

            while (true) {
                skipWhitespace();

                if (!match('|')) {
                    return node;
                }

                node = new BinaryNode(node, parseTerm(), false);
            }
        }

        Node parseTerm() {
            Node node = parseFactor();

            while (true) {
                skipWhitespace();

                if (!match('&')) {
                    return node;
                }

                node = new BinaryNode(node, parseFactor(), true);
            }
        }

        Node parseFactor() {
            skipWhitespace();

            if (peek('!') && !peek("!=")) {
                index++;
                return new NotNode(parseFactor());
            }

            if (match('(')) {
                Node node = parseExpression();
                skipWhitespace();
                require(')');
                return node;
            }

            return parseComparison();
        }

        Node parseComparison() {
            Operand left = parseOperand();
            skipWhitespace();

            Comparator comparator;

            if (match(">=")) {
                comparator = Comparator.GTE;
            } else if (match("<=")) {
                comparator = Comparator.LTE;
            } else if (match("!=")) {
                comparator = Comparator.NEQ;
            } else if (match('>')) {
                comparator = Comparator.GT;
            } else if (match('<')) {
                comparator = Comparator.LT;
            } else if (match('=')) {
                comparator = Comparator.EQ;
            } else {
                throw error("Expected comparison operator");
            }

            Operand right = parseOperand();
            return new ComparisonNode(left, right, comparator);
        }

        Operand parseOperand() {
            skipWhitespace();

            int start = index;

            while (!atEnd()) {
                char c = input.charAt(index);

                if (Character.isWhitespace(c)
                        || c == '&'
                        || c == '|'
                        || c == '('
                        || c == ')'
                        || c == '<'
                        || c == '>'
                        || c == '='
                        || c == '!') {
                    break;
                }

                index++;
            }

            if (start == index) {
                throw error("Expected value");
            }

            String token = input.substring(start, index).trim();

            try {
                return new Operand(Double.parseDouble(token), null);
            } catch (NumberFormatException ignored) {
                return new Operand(
                        null,
                        token.toLowerCase(Locale.ROOT)
                );
            }
        }

        void skipWhitespace() {
            while (!atEnd()
                    && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        boolean atEnd() {
            return index >= input.length();
        }

        int position() {
            return index;
        }

        boolean peek(char expected) {
            return !atEnd() && input.charAt(index) == expected;
        }

        boolean peek(String expected) {
            return input.startsWith(expected, index);
        }

        boolean match(char expected) {
            if (!peek(expected)) {
                return false;
            }

            index++;
            return true;
        }

        boolean match(String expected) {
            if (!peek(expected)) {
                return false;
            }

            index += expected.length();
            return true;
        }

        void require(char expected) {
            if (!match(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    message + " at position " + index
                            + " in condition '" + input + "'"
            );
        }
    }
}
