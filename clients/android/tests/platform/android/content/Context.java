package android.content;

/** JDK-only controllable permission fixture. No Android authority or microphone. */
public class Context {
    public int permission;
    public Context getApplicationContext() { return this; }
    public int checkSelfPermission(String name) {
        if (!"android.permission.RECORD_AUDIO".equals(name)) throw new AssertionError("permission fixture");
        return permission;
    }
}
