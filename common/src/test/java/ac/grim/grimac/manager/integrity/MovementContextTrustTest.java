package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MovementContextTrustTest {
 @Test void observedVelocityIsNotTrusted(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); m.beginObserved(id,MovementContextType.KNOCKBACK,"Bukkit:Velocity",900L,"v"); assertFalse(m.hasTrustedVelocityContext(id)); }
 @Test void explicitVelocityIsTrusted(){ var m=new MovementContextManager(); for(var t:new MovementContextType[]{MovementContextType.KNOCKBACK,MovementContextType.EXPLOSION,MovementContextType.LAUNCH,MovementContextType.DASH,MovementContextType.PULL}){ var id=UUID.randomUUID(); var c=m.beginTrustedVelocity(id,t,"RPGItems:test",900L,new Vector3dm(0.25D,0.45D,-0.10D),20,"test"); assertTrue(c.isTrusted()); assertTrue(c.isVelocityScoped()); assertTrue(m.hasTrustedVelocityContext(id)); }}
 @Test void legacyUnscopedVelocityTrustBecomesObserved(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); var c=m.beginTrusted(id,MovementContextType.DASH,"RPGItems:legacy",900L); assertFalse(c.isTrusted()); assertTrue(m.hasObservedVelocityContext(id)); assertFalse(m.hasTrustedVelocityContext(id)); }
 @Test void teleportDoesNotSuppressAntiKb(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); m.beginTrusted(id,MovementContextType.TELEPORT,"Plugin:test",900L); assertFalse(m.hasTrustedVelocityContext(id)); }
 @Test void trustedVelocityIsCapped(){ var m=new MovementContextManager(); var id=UUID.randomUUID(); var c=m.beginTrustedVelocity(id,MovementContextType.DASH,"RPGItems:test",10000L,new Vector3dm(1.0D,0.1D,0.0D),"test"); assertTrue(c.getRemainingMillis()<=1500L); assertTrue(c.getRemainingMillis()>0L); }
}
