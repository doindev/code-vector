package io.doindev.cvector.cli;

import picocli.CommandLine;

/**
 * Picocli version provider for {@code -V} / {@code --version} / {@code --ver}.
 *
 * <p>Emits a multi-line block with the cvector app version, the running JVM's version
 * + vendor, the JDK runtime name + version (useful for distinguishing the jpackage'd
 * trimmed runtime from a stock JDK), and the OS + arch. Knowing the bundled JRE matters
 * because the .exe ships with its own JRE — users diagnosing "works on my machine"
 * issues need to see which JDK that is, not just the host's.
 *
 * <p>App-version resolution tries three sources in order:
 * <ol>
 *   <li>The {@code jpackage.app-version} system property. Set automatically by
 *       jpackage's launcher via {@code app/cvector.cfg} ({@code java-options=
 *       -Djpackage.app-version=0.0.1}) — this is the source of truth in any
 *       distributable produced by {@code mvn -Pdist install}.</li>
 *   <li>The class package's {@code Implementation-Version} from
 *       {@code MANIFEST.MF}. Spring Boot's repackage plugin embeds the Maven
 *       {@code ${project.version}} there, so {@code java -jar cvector.jar} also
 *       reports the right number without the jpackage shim.</li>
 *   <li>A hardcoded {@link #FALLBACK_VERSION} constant. Last resort — fires when
 *       the class is loaded outside both a jpackage build and a Spring Boot jar
 *       (e.g. running from IDE bytecode directly).</li>
 * </ol>
 */
public class CvectorVersionProvider implements CommandLine.IVersionProvider {

    /** Updated by releases. Used only when neither jpackage nor the jar manifest carry a version. */
    public static final String FALLBACK_VERSION = "0.0.1";

    @Override
    public String[] getVersion() {
        String app = appVersion();
        String javaVersion = sys("java.version", "?");
        String javaVendor = sys("java.vendor", "");
        String runtime = sys("java.runtime.name", "");
        String runtimeVersion = sys("java.runtime.version", "");
        String osName = sys("os.name", "?");
        String osVersion = sys("os.version", "");
        String osArch = sys("os.arch", "");

        return new String[] {
                "cvector " + app,
                "  Java:    " + javaVersion + (javaVendor.isEmpty() ? "" : " (" + javaVendor + ")"),
                "  Runtime: " + runtime + (runtimeVersion.isEmpty() ? "" : " " + runtimeVersion),
                "  OS:      " + osName + (osVersion.isEmpty() ? "" : " " + osVersion)
                        + (osArch.isEmpty() ? "" : " (" + osArch + ")"),
        };
    }

    private static String appVersion() {
        String fromJpackage = System.getProperty("jpackage.app-version");
        if (fromJpackage != null && !fromJpackage.isBlank()) return fromJpackage;
        Package pkg = CvectorVersionProvider.class.getPackage();
        if (pkg != null) {
            String mfVersion = pkg.getImplementationVersion();
            if (mfVersion != null && !mfVersion.isBlank()) return mfVersion;
        }
        return FALLBACK_VERSION;
    }

    private static String sys(String key, String fallback) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
