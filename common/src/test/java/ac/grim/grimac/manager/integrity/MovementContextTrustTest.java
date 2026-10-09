package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MovementContextTrustTest {
 @Test void observedVelocityIsNotTrusted(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); m.beginObserved(id,MovementContextType.KNOCKBACK,"Bukkit:Velocity",900L,"v"); assertFalse(m.hasTrustedVelocityContext(id)); }
 @Test void explicitVelocityIsTrusted(){ var m=new MovementContextManager(); for(var t:new MovementContextType[]{MovementContextType.KNOCKBACK,MovementContextType.LAUNCH,MovementContextType.DASH,MovementContextType.PULL}){ var id=UUID.randomUUID(); var c=m.beginTrusted(id,t,"RPGItems:test",900L); assertTrue(c.isTrusted()); assertTrue(m.hasTrustedVelocityContext(id)); }}
 @Test void teleportDoesNotSuppressAntiKb(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); m.beginTrusted(id,MovementContextType.TELEPORT,"Plugin:test",900L); assertFalse(m.hasTrustedVelocityContext(id)); }
 @Test void trustedVelocityIsCapped(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); var c=m.beginTrusted(id,MovementContextType.DASH,"RPGItems:test",10000L); assertTrue(c.getRemainingMillis()<=1500L); assertTrue(c.getRemainingMillis()>0L); }
}
