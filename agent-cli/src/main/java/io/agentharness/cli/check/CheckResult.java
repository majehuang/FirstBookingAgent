package io.agentharness.cli.check;

/** 一项自检的结果。 */
public record CheckResult(String name, Status status, String detail) {

    public enum Status {
        OK("✓"),
        WARN("!"),
        FAIL("✗"),
        SKIP("–");

        private final String mark;

        Status(String mark) {
            this.mark = mark;
        }

        public String mark() {
            return mark;
        }
    }

    public static CheckResult ok(String name, String detail) {
        return new CheckResult(name, Status.OK, detail);
    }

    public static CheckResult warn(String name, String detail) {
        return new CheckResult(name, Status.WARN, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, Status.FAIL, detail);
    }

    public static CheckResult skip(String name, String detail) {
        return new CheckResult(name, Status.SKIP, detail);
    }

    public boolean blocking() {
        return status == Status.FAIL;
    }

    public String format() {
        return String.format("  %s  %-28s %s", status.mark(), name, detail);
    }
}
