-dontwarn android.**
-keep public class com.dsh.partitionstudio.MainActivity {
    public <init>();
    protected void onCreate(android.os.Bundle);
    protected void onSaveInstanceState(android.os.Bundle);
    public void onBackPressed();
    protected void attachBaseContext(android.content.Context);
    protected void onActivityResult(int, int, android.content.Intent);
}
-keepclassmembers class * extends android.app.Activity {
    protected void onCreate(android.os.Bundle);
    protected void onSaveInstanceState(android.os.Bundle);
    public void onBackPressed();
    protected void attachBaseContext(android.content.Context);
    protected void onActivityResult(int, int, android.content.Intent);
}
-repackageclasses 'x'
-allowaccessmodification
