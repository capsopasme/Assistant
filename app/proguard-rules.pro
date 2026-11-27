# keep line numbers in crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# started by root through app_process (ChgCtl's status-bar icon), not through the manifest
-keep class io.github.capsopasme.assistant.root.IconTool {
    public static void main(java.lang.String[]);
}
