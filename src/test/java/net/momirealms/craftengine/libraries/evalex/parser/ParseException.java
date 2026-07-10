package net.momirealms.craftengine.libraries.evalex.parser;

import net.momirealms.craftengine.libraries.evalex.BaseException;

/**
 * Test-only stub of CraftEngine's relocated EvalEx {@code ParseException}. The real class is loaded at
 * runtime via Libby; the published {@code craft-engine-core} jar references the relocated name but does
 * not ship the class. Without this stub, loading {@code ConstantNumberProvider$Factory} (referenced from
 * {@code ConfigConstants.<clinit>}) fails to verify its exception-handler types under the test classpath.
 */
public class ParseException extends BaseException {
    public ParseException(String message) {
        super(message);
    }
}
