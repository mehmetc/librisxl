package se.kb.libris.whelks.plugin;

public interface Importer extends WhelkAware {
    int doImport(String dataset, int maxNrOfDocsToImport, boolean silent, boolean picky);
}

