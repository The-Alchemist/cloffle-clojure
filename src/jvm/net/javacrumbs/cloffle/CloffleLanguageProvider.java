package net.javacrumbs.cloffle;

import com.oracle.truffle.api.TruffleFile.FileTypeDetector;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.provider.TruffleLanguageProvider;

import java.util.Collections;
import java.util.List;

/**
 * ServiceLoader-provided registration for the cloffle Truffle language.
 * Required because annotation-based discovery does not find our language in some environments
 * (e.g. Maven exec, IDE runs, or when classloader isolation is in effect).
 */
@TruffleLanguage.Registration(id = "cloffle", name = "Cloffle")
public final class CloffleLanguageProvider extends TruffleLanguageProvider {

    @Override
    protected String getLanguageClassName() {
        return "net.javacrumbs.cloffle.Clojure";
    }

    @Override
    protected Object create() {
        return new Clojure();
    }

    @Override
    protected List<FileTypeDetector> createFileTypeDetectors() {
        return Collections.emptyList();
    }

    @Override
    protected java.util.Collection<String> getServicesClassNames() {
        return Collections.emptyList();
    }
}
