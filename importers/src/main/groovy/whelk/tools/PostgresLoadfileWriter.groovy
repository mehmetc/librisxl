package whelk.tools

import whelk.Document
import whelk.converter.MarcJSONConverter
import whelk.converter.marc.MarcFrameConverter
import whelk.importer.MySQLLoader
import whelk.util.LegacyIntegrationTools
import groovy.util.logging.Slf4j as Log

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Writes documents into a PostgreSQL load-file, which can be efficiently imported into lddb
 */
@Log
class PostgresLoadfileWriter
{
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CONVERSIONS_PER_THREAD = 200;

    // USED FOR DEV ONLY, MUST _NEVER_ BE SET TO TRUE ONCE XL GOES INTO PRODUCTION. WITH THIS SETTING THE IMPORT WILL
    // _SKIP_ DOCUMENTS THAT FAIL CONVERSION, RESULTING IN POTENTIAL DATA LOSS IF USED WHEN IMPORTING TO A PRODUCTION XL
    private static final boolean FAULT_TOLERANT_MODE = true;

    private static MarcFrameConverter s_marcFrameConverter;
    private static BufferedWriter s_mainTableWriter;
    private static BufferedWriter s_identifiersWriter;
    private static Thread[] s_threadPool;
    private static Vector<String> s_failedIds = new Vector<String>();


    // Abort on unhandled exceptions, including those on worker threads.
    static
    {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
        {
            @Override
            void uncaughtException(Thread thread, Throwable throwable)
            {
                System.out.println("PANIC ABORT, unhandled exception:\n");
                throwable.printStackTrace();
                System.exit(-1);
            }
        });
    }

    public static void dump(String exportFileName, String collection, String connectionUrl)
    {
        Vector<HashMap> m_outputQueue = new Vector<HashMap>(CONVERSIONS_PER_THREAD);

        if (FAULT_TOLERANT_MODE)
            System.out.println("\t**** RUNNING IN FAULT TOLERANT MODE, DOCUMENTS THAT FAIL CONVERSION WILL BE SKIPPED.\n" +
                    "\tIF YOU ARE IMPORTING TO A PRODUCTION XL, ABORT NOW!! AND RECOMPILE WITH FAULT_TOLERANT_MODE=false");

        s_marcFrameConverter = new MarcFrameConverter();
        s_mainTableWriter = Files.newBufferedWriter(Paths.get(exportFileName), Charset.forName("UTF-8"));
        s_identifiersWriter = Files.newBufferedWriter(Paths.get(exportFileName+"_identifiers"), Charset.forName("UTF-8"));
        s_threadPool = new Thread[THREAD_COUNT];

        def loader = new MySQLLoader(connectionUrl, collection);

        def counter = 0
        def startTime = System.currentTimeMillis()

        try
        {
            loader.run { doc, specs ->

                if (isSuppressed(doc))
                    return

                String oldStyleIdentifier = "/"+collection+"/"+getControlNumber(doc)
                String id = LegacyIntegrationTools.generateId(oldStyleIdentifier)

                def manifest = [(Document.ID_KEY):id,(Document.COLLECTION_KEY):collection, (Document.ALTERNATE_ID_KEY): [oldStyleIdentifier]]

                if (specs != null)
                    addSetSpecs(manifest, specs)

                Map documentMap = new HashMap(2)
                documentMap.put("record", doc)
                documentMap.put("manifest", manifest)
                m_outputQueue.add(documentMap);

                if (m_outputQueue.size() >= CONVERSIONS_PER_THREAD)
                {
                    flushOutputQueue(m_outputQueue);
                    m_outputQueue = new Vector<HashMap>(CONVERSIONS_PER_THREAD);
                }

                if (++counter % 1000 == 0) {
                    def elapsedSecs = (System.currentTimeMillis() - startTime) / 1000
                    if (elapsedSecs > 0) {
                        def docsPerSec = counter / elapsedSecs
                        println "Working. Currently $counter documents saved. Crunching $docsPerSec docs / s"
                    }
                }
            }
        } finally {
            for (int i = 0; i < THREAD_COUNT; ++i)
            {
                if (s_threadPool[i] != null)
                    s_threadPool[i].join();
            }
            s_mainTableWriter.close()
            s_identifiersWriter.close()
        }

        def endSecs = (System.currentTimeMillis() - startTime) / 1000
        println "Done. Processed $counter documents in $endSecs seconds."
    }

    private static boolean isSuppressed(Map doc)
    {
        def fields = doc.get("fields")
        for (def field : fields)
        {
            if (field.get("599") != null)
            {
                def field599 = field.get("599")
                if (field599.get("subfields") != null)
                {
                    def subfields = field599.get("subfields")
                    for (def subfield : subfields)
                    {
                        if (subfield.get("a").equals("SUPPRESSRECORD"))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private static String getControlNumber(Map doc)
    {
        def fields = doc.get("fields")
        for (def field : fields)
        {
            if (field.get("001") != null)
                return field.get("001");
        }
        return null
    }

    private static void addSetSpecs(Map manifest, List specs)
    {
        if (specs.size() == 0)
            return
        
        def extradata = manifest.get("extraData", [:])
        def setSpecs = extradata.get("oaipmhSetSpecs", [])

        for (String spec : specs)
        {
            setSpecs.add(spec);
        }
    }

    private static void flushOutputQueue(Vector<HashMap> threadWorkLoad)
    {
        // Find a suitable thread from the pool to do the conversion

        int i = 0;
        while(true)
        {
            i++;
            if (i == THREAD_COUNT)
            {
                i = 0;
                Thread.yield();
            }

            if (s_threadPool[i] == null || s_threadPool[i].state == Thread.State.TERMINATED)
            {
                s_threadPool[i] = new Thread(new Runnable()
                {
                    void run()
                    {
                        for (HashMap dm : threadWorkLoad)
                        {
                            dm.manifest[Document.CHANGED_IN_KEY] = "vcopy";
                            Document doc = null;
                            if (FAULT_TOLERANT_MODE)
                            {
                                try
                                {
                                    doc = new Document(dm.record, dm.manifest);
                                    doc = s_marcFrameConverter.convert(doc);
                                    writeDocumentToLoadFile(doc);
                                } catch (Exception e)
                                {
                                    String voyagerId = dm.manifest.get(Document.ALTERNATE_ID_KEY)[0];
                                    s_failedIds.add(voyagerId);
                                }
                            }
                            else
                            {
                                doc = new Document(MarcJSONConverter.toJSONMap(dm.record), dm.manifest);
                                doc = s_marcFrameConverter.convert(doc);
                                writeDocumentToLoadFile(doc);
                            }
                        }
                    }
                });
                s_threadPool[i].start();
                return;
            }
        }
    }

    private static synchronized void writeDocumentToLoadFile(Document doc)
    {
        /* columns:
        id text not null unique primary key,
        data jsonb not null,
        manifest jsonb not null,
        quoted jsonb,
        created timestamp with time zone not null default now(),
        modified timestamp with time zone not null default now(),
        deleted boolean default false*/

        final char delimiter = '\t';
        final String nullString = "\\N";

        final delimiterString = new String(delimiter);

        String quoted = doc.getQuotedAsString();

        doc.findIdentifiers()
        List<String> identifiers = doc.getIdentifiers();

        // Write to main table file

        s_mainTableWriter.write(doc.getId());
        s_mainTableWriter.write(delimiter);
        s_mainTableWriter.write( doc.getDataAsString().replace("\\", "\\\\").replace(delimiterString, "\\"+delimiterString) );
        s_mainTableWriter.write(delimiter);
        s_mainTableWriter.write( doc.getManifestAsJson().replace("\\", "\\\\").replace(delimiterString, "\\"+delimiterString) );
        s_mainTableWriter.write(delimiter);
        if (quoted)
            s_mainTableWriter.write(quoted.replace("\\", "\\\\").replace(delimiterString, "\\"+delimiterString));
        else
            s_mainTableWriter.write(nullString);

        // remaining values have defaults.

        s_mainTableWriter.newLine();

        // Write to identifiers table file

        /* columns:
        id text not null,
        identifier text not null -- unique
        */

        for (String identifier : identifiers)
        {
            s_identifiersWriter.write(doc.getId());
            s_identifiersWriter.write(delimiter);
            s_identifiersWriter.write(identifier);

            s_identifiersWriter.newLine();
        }
    }
}