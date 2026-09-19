package net.javacrumbs.cloffle.benchmark.tuplepea;

import com.oracle.truffle.api.TruffleFile.FileTypeDetector;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.provider.TruffleLanguageProvider;

import java.util.Collections;
import java.util.List;

@TruffleLanguage.Registration(id = TuplePeaLanguage.ID, name = "Pea")
public final class TuplePeaLanguageProvider extends TruffleLanguageProvider {

    @Override
    protected String getLanguageClassName() {
        return TuplePeaLanguage.class.getName();
    }

    @Override
    protected Object create() {
        return new TuplePeaLanguage();
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
