package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VelocityIntegrityAuditTest {
 private static String src(String r)throws Exception{return Files.readString(Path.of("src/main/java/ac/grim/grimac").resolve(r));}
 @Test void antiKbDefaultsReapplyOff()throws Exception{String s=src("checks/impl/velocity/KnockbackHandler.java");assertTrue(s.contains("getBooleanElse(\"Knockback.enforcement.enabled\", false)"));assertTrue(s.contains("hasTrustedVelocityContext(player.uuid)"));assertTrue(s.contains("flag("));}
 @Test void simulationStillFlags()throws Exception{String s=src("checks/impl/prediction/OffsetHandler.java");assertTrue(s.contains("trustedVelocityContext"));assertTrue(s.contains("boolean accepted = flag("));}
}
