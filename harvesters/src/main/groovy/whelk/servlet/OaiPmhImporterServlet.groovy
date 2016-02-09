package whelk.servlet

import groovy.util.logging.Slf4j as Log
import org.codehaus.jackson.map.ObjectMapper
import org.picocontainer.Characteristics
import org.picocontainer.PicoContainer
import whelk.Whelk
import whelk.component.PostgreSQLComponent
import whelk.component.Storage
import whelk.converter.marc.MarcFrameConverter
import whelk.harvester.LibrisOaiPmhHarvester
import whelk.harvester.OaiPmhHarvester
import whelk.util.PropertyLoader

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Created by markus on 15-09-03.
 */
@Log
class OaiPmhImporterServlet extends HttpServlet {

    PicoContainer pico
    int scheduleDelaySeconds = 5
    int scheduleIntervalSeconds = 30
    Properties props = new Properties()
    private Map<String,ScheduledJob> jobs = [:]

    static String SETTINGS_PFX = "harvester:"

    ScheduledExecutorService ses

    static final ObjectMapper mapper = new ObjectMapper()


    public OaiPmhImporterServlet() {
        log.info("Starting oaipmhimporter.")

        props = PropertyLoader.loadProperties("secret", "oaipmh")

        pico = Whelk.getPreparedComponentsContainer(props)

        pico.as(Characteristics.USE_NAMES).addComponent(OaiPmhHarvester.class)
        pico.as(Characteristics.USE_NAMES).addComponent(LibrisOaiPmhHarvester.class)
        pico.addComponent(new MarcFrameConverter())

        pico.start()

        log.info("Started ...")
    }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) {
        def storage = pico.getComponent(PostgreSQLComponent)
        String html, json
        if (jobs) {
            //List collections = props.scheduledDatasets.split(",")
            List collections = storage.loadSettings("oaipmh").keySet() as List
            def state = [:]
            StringBuilder table = new StringBuilder("<table cellspacing=\"10\"><tr><th>&nbsp;</th>")
            table.append("<form method=\"post\">")

            Set catSet = new TreeSet<String>()

            for (collection in collections) {
                state[collection] = storage.loadSettings(SETTINGS_PFX+collection)
                catSet.addAll(state[collection].keySet())
                table.append("<th>$collection</th>")
            }
            table.append("</tr>")
            List categories = catSet.toList()

            int i = 0
            for (cat in categories) {
                table.append("<tr><td align=\"right\"><b>$cat</b></td>")
                for (collection in collections) {
                    table.append("<td>${state.get(collection).get(cat) != null ? state.get(collection).get(cat) : "&nbsp;"}</td>")
                }
                table.append("</tr>")
            }
            table.append("<tr><td><input type=\"submit\" name=\"action_all\" value=\"stop all\"></td>")
            for (collection in collections) {
                table.append("<td><input type=\"submit\" name=\"action_${collection}\" value=\"${jobs[collection]?.active ? "stop" : "start"}\">")
                if (jobs[collection] && !jobs[collection].active) {
                    String lastImportDate = jobs[collection].getLastImportValue().format("yyyy-MM-dd'T'HH:mm")
                    table.append("&nbsp;<input type=\"submit\" name=\"reset_${collection}\" value=\"reload $collection from\"/>&nbsp;<input type=\"datetime-local\" name=\"datevalue\" value=\"${lastImportDate}\"/>")
                }
                table.append("</td>")
            }
            table.append("</tr>")

            table.append("</form></table>")
            StringBuilder availableHarvesters = new StringBuilder("<select name=\"harvesterClass\">")
            for (h in pico.getComponents(OaiPmhHarvester.class)) {
                availableHarvesters.append("<option value=\"${h.getClass().getName()}\">${h.getClass().getName()}</option>")
            }
            availableHarvesters.append("</select>")
            String newService = """
                <p><b>Add OAI-PMH service</b></p>
                <p>
                <form method="post">
                <input type="text" name="serviceLabel" value="Label"/>
                ${availableHarvesters.toString()}
                <input type="text" name="serviceUrl" value="serviceUrl"/>
                <input type="text" name="username" value="username"/>
                <input type="text" name="password" value="password"/>
                <input type="submit" name="setupService" value="create"/>
                </p>
            """

            html = """
                <html><head><title>OAIPMH Harvester control panel</title></head>
                <body>
                System version: ${props.version}<br><br>
                ${table.toString()}
                ${newService}
                </form>
                """
            json = mapper.writeValueAsString(state)
        } else {

            html = """
                <html><head><title>OAIPMH Harvest Disabled</title></head>
                <body>

                HARVESTER DISABLED<br/>
                System version ${props.version} is incompatible with data version ${loadDataVersion()}.
                """
            json = mapper.writeValueAsString(["state":"disabled", "system.version":props.version, "data.version":loadDataVersion()])
        }
        PrintWriter out = response.getWriter();

        if (request.getPathInfo() == "/json") {
            response.setContentType("application/json");
            out.print(json);
        } else {
            response.setContentType("text/html");
            out.print(html);
        }

        out.flush();
    }

    void doPost(HttpServletRequest request, HttpServletResponse response) {
        log.debug("Received post request. Got this: ${request.getParameterMap()}")
        if (request.getParameter("setupService") == "create") {
            Storage storage = pico.getComponent(PostgreSQLComponent.class)
            Map oaipmhSettings = storage.loadSettings("oaipmh")
            Map service = [
                    "harvesterClass": request.getParameter("harvesterClass"),
                    "serviceUrl": request.getParameter("serviceUrl"),
                    "username": request.getParameter("username"),
                    "password": request.getParameter("password")
            ]
            oaipmhSettings.put(request.getParameter("serviceLabel"), service)
            storage.saveSettings("oaipmh", oaipmhSettings)
            ses.shutdown()
            ses.awaitTermination(1, TimeUnit.MINUTES)
            init()
        } else {
            for (reqs in request.getParameterNames()) {
                if (reqs == "action_all") {
                    for (job in jobs) {
                        job.value.disable()
                    }
                } else if (reqs.startsWith("reset_")) {
                    log.debug("Loading job for ${reqs.substring(6)}")
                    log.debug("Got these jobs: $jobs")
                    def job = jobs.get(reqs.substring(6))
                    Date startDate = Date.parse("yyyy-MM-dd'T'HH:mm", request.getParameter("datevalue"))
                    log.debug("Resetting harvester for ${job.collection} to $startDate")
                    job.setStartDate(startDate)
                    //job.enable()
                } else if (reqs.startsWith("action_")) {
                    jobs.get(reqs.substring(7)).toggleActive()
                }
            }
        }
        response.sendRedirect(request.getRequestURL().toString())
    }

    Map loadOaiPmhSettings() {
        Map settings = pico.getComponent(PostgreSQLComponent.class).loadSettings("oaipmh")

    }

    void init() {
        if (props.getProperty("version").startsWith(loadDataVersion())) {
            log.info("Initializing OAIPMH harvester. System version: ${pico.getComponent(Whelk.class).version}")
            Storage storage = pico.getComponent(PostgreSQLComponent.class)

            //List collections = props.scheduledDatasets.split(",")
            Map oaipmhSettings = storage.loadSettings("oaipmh")

            List collections = oaipmhSettings.keySet() as List

            ses = Executors.newScheduledThreadPool(collections.size())
            for (collection in collections) {
                log.info("Setting up schedule for $collection")
                try {
                    def job = new ScheduledJob(pico.getComponent(Class.forName(oaipmhSettings[collection].harvesterClass)),
                            "${SETTINGS_PFX}${collection}",
                            oaipmhSettings[collection].serviceUrl,
                            oaipmhSettings[collection].username,
                            oaipmhSettings[collection].password,
                            storage)
                    //def oldjob = new ScheduledJob(pico.getComponent(OaiPmhHarvester.class), collection, storage)
                    jobs[collection] = job

                    ses.scheduleWithFixedDelay(job, scheduleDelaySeconds, scheduleIntervalSeconds, TimeUnit.SECONDS)
                } catch (RejectedExecutionException ree) {
                    log.error("execution failed", ree)
                } catch (Exception e) {
                    log.error("Failed to set up job for $collection", e)
                }
            }
            log.info("scheduler started")
        } else {
            log.error("INCOMPATIBLE VERSIONS! Not scheduling any harvesters.")
        }
    }

    String loadDataVersion() {
        def systemSettings = pico.getComponent(PostgreSQLComponent.class).loadSettings("system")
        return systemSettings.get("version")
    }
}

@Log
class ScheduledJob implements Runnable {

    static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssX"

    String collection, serviceUrl, username, password
    OaiPmhHarvester harvester
    PostgreSQLComponent storage
    Map whelkState = null
    boolean active = true
    final static long WEEK_MILLIS = 604800000

    ScheduledJob(OaiPmhHarvester imp, String coll, String url, String uname, String pword, PostgreSQLComponent pg) {
        this.harvester = imp
        this.collection = coll
        this.storage = pg
        this.serviceUrl = url
        this.username = uname
        this.password = pword
        assert storage
        assert collection
    }

    void toggleActive() {
        active = !active
        loadWhelkState().put("status", (active ? "IDLE" : "STOPPED"))
        storage.saveSettings(collection, whelkState)
    }

    void disable() {
        active = false
        loadWhelkState().put("status", "STOPPED")
        storage.saveSettings(collection, whelkState)
    }

    void enable() {
        active = true
        loadWhelkState().put("status", "IDLE")
        storage.saveSettings(collection, whelkState)
    }

    void setStartDate(Date startDate) {
        loadWhelkState().put("lastImport", startDate.format(DATE_FORMAT))
        storage.saveSettings(collection, whelkState)
    }

    Date getLastImportValue() {
        String dateString = loadWhelkState().get("lastImport")
        if (!dateString) {
            return new Date(new Date().getTime() - WEEK_MILLIS)
        } else {
            return Date.parse(DATE_FORMAT, dateString)
        }
    }

    Map loadWhelkState() {
        if (!whelkState) {
            log.debug("Loading current state from storage ...")
            whelkState = storage.loadSettings(collection)
            log.debug("Loaded state for $collection : $whelkState")
        }
        return whelkState
    }

    void run() {
        if (active) {
            loadWhelkState()
            log.debug("Current whelkstate: $whelkState")
            try {
                String lastImport = whelkState.get("lastImport")
                Date currentSince
                Date nextSince = new Date()
                if (lastImport) {
                    log.trace("Parsing $lastImport as date")
                    currentSince = Date.parse(DATE_FORMAT, lastImport)
                    nextSince = new Date(currentSince.getTime() + 1000)
                    log.trace("Next since (upped by 1 second): $nextSince")
                } else {
                    def lastWeeksDate = nextSince[Calendar.DATE] - 7
                    nextSince.set(date: lastWeeksDate)
                    currentSince = nextSince
                    log.info("Importer has no state for last import from $collection. Setting last week (${nextSince})")
                }
                //nextSince = new Date(0) //sneeking past next date
                if (nextSince.after(new Date())) {
                    log.warn("Since is slipping ... Is now ${nextSince}. Resetting to now()")
                    nextSince = new Date()
                }
                log.debug("Executing OAIPMH import for $collection since $nextSince from ${harvester.serviceUrl}")
                whelkState.put("status", "RUNNING")

                storage.saveSettings(collection, whelkState)
                //def result = harvester.doImport(collection, null, -1, true, true, nextSince)
                def result = harvester.harvest(serviceUrl, username, password, "ListRecords", "marcxml", nextSince)
                log.trace("Import completed, result: $result")

                if (result.numberOfDocuments > 0 || result.numberOfDocumentsDeleted > 0 || result.numberOfDocumentsSkipped > 0) {
                    log.debug("Imported ${result.numberOfDocuments} documents and deleted ${result.numberOfDocumentsDeleted} for $collection. Last record has datestamp: ${result.lastRecordDatestamp.format(DATE_FORMAT)}")
                    whelkState.put("lastImportNrImported", result.numberOfDocuments)
                    whelkState.put("lastImportNrDeleted", result.numberOfDocumentsDeleted)
                    whelkState.put("lastImportNrSkipped", result.numberOfDocumentsSkipped)
                    whelkState.put("lastImport", result.lastRecordDatestamp.format(DATE_FORMAT))

                } else {
                    log.debug("Imported ${result.numberOfDocuments} document for $collection.")
                    whelkState.put("lastImport", currentSince.format(DATE_FORMAT))
                }
                whelkState.put("status", "IDLE")
                whelkState.put("lastRunNrImported", result.numberOfDocuments)
                whelkState.put("lastRun", new Date().format(DATE_FORMAT))
            } catch (Exception e) {
                log.error("Something failed: ${e.message}", e)
            } finally {
                log.debug("Saving state $whelkState")
                storage.saveSettings(collection, whelkState)
            }
        }
    }

}

