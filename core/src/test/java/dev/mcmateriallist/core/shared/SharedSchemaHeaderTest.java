package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.persistence.SharedSchemaHeader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedSchemaHeaderTest {
    private StoreStatus schema(String version) {
        return SharedSchemaHeader.classify("{\"payload\":{},\"schema\":" + version + "}", Set.of("schema", "payload"));
    }
    @Test void integralFutureSpellingDoesNotExpandNumbersOrAcceptFractions() {
        for (String version : List.of("2", "20e-1", "2.00", "0.02e2", "2E+3", "123456789012345678901234567890", "1e" + "9".repeat(10000)))
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, schema(version), version.substring(0, Math.min(20, version.length())));
        for (String version : List.of("0", "-2", "1.1", "99.001", "21e-1", "1e-999999999999999999999", "1.0", "1e0", "\"99\"", "null", "01", "2e", "2."))
            assertEquals(StoreStatus.CORRUPT, schema(version), version);
        assertEquals(StoreStatus.OK, schema("1"));
    }
    @Test void duplicateMalformedAndMissingCurrentFieldsRemainCorrupt() {
        for (String json : List.of("{\"schema\":1,\"schema\":2,\"payload\":{}}", "{\"schema\":2,\"schema\":1}",
            "{\"schema\":1,\"payload\":{},\"payload\":{}}", "{\"schema\":1}", "{\"schema\":1,\"unknown\":0,\"payload\":{}}",
            "{\"schema\":1,\"payload\":{}} trailing", "{\"schema\":1,\"payload\":" + "[".repeat(10000)))
            assertEquals(StoreStatus.CORRUPT, SharedSchemaHeader.classify(json, Set.of("schema", "payload")));
    }
}
