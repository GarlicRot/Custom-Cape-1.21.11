package org.example.sharedcapes.publish;

/** No JUnit dependency: runs via a dedicated Gradle JavaExec verification task. */
public final class CapeSelectionPolicyTest {
    private static int checks;

    private static void check(String name, boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError(name + " expected " + expected + ", got " + actual);
        checks++;
        System.out.println("PASS: " + name);
    }

    public static void main(String[] args) {
        check("Selected PNG publishes automatically", CapeSelectionPolicy.shouldPublish(true, "my-cape.png"), true);
        check("None unpublishes", CapeSelectionPolicy.shouldPublish(true, "None"), false);
        check("No selection does not publish", CapeSelectionPolicy.shouldPublish(true, null), false);
        check("Empty selection does not publish", CapeSelectionPolicy.shouldPublish(true, ""), false);
        check("Capes module disabled unpublishes", CapeSelectionPolicy.shouldPublish(false, "my-cape.png"), false);
        System.out.println("OK: " + checks + " automatic-sharing policy checks passed");
    }
}
