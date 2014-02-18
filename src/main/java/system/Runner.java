package system;

import com.amazonaws.mturk.service.exception.AccessKeyException;
import com.amazonaws.mturk.service.exception.InsufficientFundsException;
import csv.*;
import org.apache.log4j.*;
import org.dom4j.DocumentException;
import survey.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import qc.QCMetrics.FreqProb;
import system.interfaces.ResponseManager;
import system.interfaces.SurveyPoster;
import system.interfaces.Task;
import system.localhost.LocalResponseManager;
import system.localhost.LocalSurveyPoster;
import system.mturk.MturkResponseManager;
import system.mturk.MturkSurveyPoster;
import system.mturk.MturkTask;

public class Runner {

    public static class BoxedBool{
        private boolean interrupt;
        public BoxedBool(boolean interrupt){
            this.interrupt = interrupt;
        }
        public void setInterrupt(boolean bool){
            this.interrupt = bool;
        }
        public boolean getInterrupt(){
            return interrupt;
        }
    }

    // everything that uses MturkResponseManager should probably use some parameterized type to make this more general
    // I'm hard-coding in the mturk stuff for now though.
    private static final Logger LOGGER = Logger.getRootLogger();
    private static FileAppender txtHandler;
    private static int totalHITsGenerated;
    public static HashMap<BackendType, ResponseManager> responseManagers = new HashMap<BackendType, ResponseManager>();
    public static HashMap<BackendType, SurveyPoster> surveyPosters = new HashMap<BackendType, SurveyPoster>();
    static {
        responseManagers.put(BackendType.LOCALHOST, new LocalResponseManager());
        surveyPosters.put(BackendType.LOCALHOST, new LocalSurveyPoster());
        responseManagers.put(BackendType.MTURK, new MturkResponseManager());
        surveyPosters.put(BackendType.MTURK, new MturkSurveyPoster());
    }

    public static int recordAllTasksForSurvey(Survey survey, BackendType backendType) throws IOException, SurveyException, DocumentException {
        Record record = MturkResponseManager.manager.get(survey.sid);
        int allHITs = record.getAllTasks().length;
        String hiturl = "", msg;
        int responsesAdded = 0;
        for (Task hit : record.getAllTasks()) {
            hiturl = MturkSurveyPoster.makeHITURL((MturkTask) hit);
            ResponseManager responseManager = responseManagers.get(backendType);
            responsesAdded = responseManager.addResponses(survey, hit);
        }
        msg = String.format("Polling for responses for Tasks at %s (%d total)"
                , hiturl
                , record.responses.size());
        if (allHITs > totalHITsGenerated) {
            System.out.println("total Tasks generated: "+record.getAllTasks().length);
            totalHITsGenerated = allHITs;
            System.out.println(msg);
        }
        LOGGER.info(msg);
        return responsesAdded;
    }

    public static Thread makeResponseGetter(final Survey survey, final BoxedBool interrupt, final BackendType backendType){
        // grab responses for each incomplete survey in the responsemanager
        return new Thread(){
            @Override
            public void run(){
                int waittime = 1;
                while (!interrupt.getInterrupt()){
                    System.out.println("Checking for responses");
                    ResponseManager responseManager = responseManagers.get(backendType);
                    assert(responseManager!=null);
                    while(!interrupt.getInterrupt()){
                        try {
                            int n = recordAllTasksForSurvey(survey, backendType);
                            if (n > 0)
                                waittime = 2;
                        } catch (IOException e) {
                            e.printStackTrace(); System.exit(-1);
                        } catch (SurveyException e) {
                            e.printStackTrace(); System.exit(-1);
                        } catch (DocumentException e) {
                            e.printStackTrace(); System.exit(-1); //To change body of catch statement use File | Settings | File Templates.
                        }
                        ResponseManager.chill(waittime);
                        if (waittime > ResponseManager.maxwaittime)
                            waittime = 1;
                        else waittime *= 2;
                    }
                    // if we're out of the loop, expire and process the remaining HITs
                    System.out.println("\n\tDANGER ZONE\n");
                    ResponseManager.chill(3);
                    Record record = responseManager.manager.get(survey.sid);
                    assert(responseManager!=null);
                    for (Task task : record.getAllTasks()){
                        boolean expiredAndAdded = false;
                        while (! expiredAndAdded) {
                            try {
                                responseManager.makeTaskUnavailable(task);
                                responseManager.addResponses(survey, task);
                                expiredAndAdded = true; 
                            } catch (Exception e) {
                                System.out.println("something in the response getter thread threw an error.");
                                e.printStackTrace();
                                MturkResponseManager.chill(1);
                            }
                        }
                    }
                    MturkResponseManager.removeRecord(record);
                }
            }
        };
    }

    public static boolean stillLive(Survey survey) throws IOException {
        Record record = MturkResponseManager.manager.get(survey.sid);
        if (record==null)
            return false;
        boolean done = record.qc.complete(record.responses, record.library.props);
        return ! done;
    }

    public static void writeResponses(Survey survey, Record record){
        for (SurveyResponse sr : record.responses) {
            synchronized(sr) {
                if (!sr.recorded) {
                    BufferedWriter bw = null;
                    System.out.println("writing "+sr.srid);
                    try {
                        String sep = ",";
                        System.out.println(record.outputFileName);
                        File f = new File(record.outputFileName);
                        bw = new BufferedWriter(new FileWriter(f, true));
                        if (! f.exists() || f.length()==0)
                            bw.write(SurveyResponse.outputHeaders(survey));
                        String txt = sr.outputResponse(survey, sep);
                        System.out.println(txt);
                        bw.write(sr.outputResponse(survey, sep));
                        sr.recorded = true;
                        bw.close();
                        System.out.println("Wrote one response");
                    } catch (IOException ex) {
                        System.out.println(ex);
                        LOGGER.warn(ex);
                    } finally {
                        try {
                            if (bw != null) bw.close();
                        } catch (IOException ex) {
                            LOGGER.warn(ex);
                        }
                    }
                }
            }
        }
    }

    public static void writeBots(Survey survey, Record record) {
        synchronized (record) {
            String sep = ",";
            for (int i = 0 ; i < record.botResponses.size() ; i++) {
                SurveyResponse sr = record.botResponses.get(i);
                try {
                    File f = new File(record.outputFileName+"_suspectedbots");
                    BufferedWriter bw = new BufferedWriter(new FileWriter(f, true));
                    bw.write(sr.outputResponse(survey, sep));
                    bw.close();
                } catch (IOException io) {
                    LOGGER.warn(io);
                    i--;
                }
            }
            try {
                File f = new File(record.outputFileName+"_fmap");
                BufferedWriter bw = new BufferedWriter(new FileWriter(f, true));
                FreqProb fp = new FreqProb(survey, record.responses);
                for (String quid : fp.qHistograms.keySet()){
                    bw.write(quid + "\n");
                    for (Map.Entry<String, Integer> o : fp.qHistograms.get(quid).entrySet()) {
                        bw.write(String.format("%s%s%s%s", o.getKey(), o.getValue(), ":", sep));
                    }
                    bw.write("\n");
                }
                bw.close();
            } catch (IOException io) {
                LOGGER.warn(io);
            }
        }
    }

    public static Thread makeWriter(final Survey survey, final BoxedBool interrupt){
        //writes hits that correspond to current jobs in memory to their files
        return new Thread(){
            @Override
            public void run(){
                Record record;
                do {
                    synchronized (ResponseManager.manager) {
                        while (ResponseManager.manager.get(survey.sid)==null) {
                            try {
                                System.out.println("waiting...");
                                ResponseManager.manager.wait();
                            } catch (InterruptedException ie) { LOGGER.warn(ie); }
                        }
                    }
                    record = MturkResponseManager.manager.get(survey.sid);
                    writeResponses(survey, record);
                    ResponseManager.chill(3);
                } while (!interrupt.getInterrupt());
                // clean up
                synchronized (record) {
                    System.out.print("Writing straggling data...");
                    try {
                        record.wait();
                    } catch (InterruptedException e) { LOGGER.warn(e); }
                    writeResponses(survey, record);
                    writeBots(survey, record);
                    MturkResponseManager.manager.put(survey.sid, null);
                    System.out.println("done.");
                }
            }
        };
    }

    public static ResponseManager makeResponseManagerForType(BackendType backendType) {
        switch (backendType) {
            case MTURK:
                return new MturkResponseManager();
            case LOCALHOST:
                return new LocalResponseManager();
        }
        throw new RuntimeException("Unknown backend type : "+backendType.name());
    }

    public static SurveyPoster makeSurveyPosterForType(BackendType backendType) {
        switch (backendType) {
            case MTURK:
                return new MturkSurveyPoster();
            case LOCALHOST:
                return new LocalSurveyPoster();
        }
        throw new RuntimeException("Unknown backend type: " + backendType.name());
    }

    public static void run(final Record record, final BoxedBool interrupt, final BackendType backendType)
            throws SurveyException, IOException, ParseException {
        Survey survey = record.survey;
        ResponseManager responseManager = responseManagers.get(backendType);
        SurveyPoster surveyPoster = surveyPosters.get(backendType);
        do {
            if (!interrupt.getInterrupt() && surveyPoster.postMore(responseManager, survey)) {
                List<Task> tasks = surveyPoster.postSurvey(responseManager, record);
                System.out.println("num tasks posted from Runner.run " + tasks.size());
                ResponseManager.chill(2);
            }
            ResponseManager.chill(2);
        } while (stillLive(survey) && !interrupt.getInterrupt());
       ResponseManager.chill(10);
        synchronized (record) {
            for (Task task : responseManager.listAvailableTasksForRecord(ResponseManager.getRecord(survey)))
                responseManager.makeTaskUnavailable(task);
        }
        interrupt.setInterrupt(true);
    }

    public static void runAll(String file, String sep, BackendType backendType)
            throws InvocationTargetException, SurveyException, IllegalAccessException, NoSuchMethodException, IOException, ParseException, InterruptedException {
        while (true) {
            try {
                BoxedBool interrupt = new BoxedBool(false);
                CSVParser csvParser = new CSVParser(new CSVLexer(file, sep));
                Survey survey = csvParser.parse();
                // make sure survey is well formed
                Rules.ensureBranchForward(survey, csvParser);
                Rules.ensureCompactness(csvParser);
                Rules.ensureNoDupes(survey);
                Rules.ensureRandomizedBlockConsistency(survey, csvParser);
                // create and store the record
                Record record = new Record(survey, new Library(), backendType);
                MturkResponseManager.addRecord(record);
                Thread writer = makeWriter(survey, interrupt);
                Thread responder = makeResponseGetter(survey, interrupt, backendType);
                responder.setPriority(Thread.MAX_PRIORITY);
                writer.start();
                responder.start();
                Runner.run(record, interrupt, backendType);
                writer.join();
                responder.join();
                System.exit(0);
            } catch (InsufficientFundsException ife) {
                System.out.println("Insufficient funds in your Mechanical Turk account. Would you like to:\n" +
                        "[1] Add more money to your account and retry\n" +
                        "[2] Quit\n");
                int i = 0;
                while(i!=1 && i!=2){
                    System.out.println("Type number corresponding to preference: ");
                    i = new Scanner(System.in).nextInt();
                    if (i==2)
                        System.exit(1);
                }
            } catch (AccessKeyException aws) {
                System.out.println(String.format("There is a problem with your access keys: %s; Exiting...", aws.getMessage()));
                MturkResponseManager.chill(2);
                System.exit(0);
            }
        }
    }

    public static void main(String[] args)
            throws IOException, SurveyException, InterruptedException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, ParseException {


        if (args.length!=4) {
            System.err.println("USAGE: <survey.csv> <sep> <expire> <backend>\r\n"
                + "survey.csv  the relative path to the survey csv file from the current location of execution.\r\n"
                + "sep         the field separator (should be a single char or 2-char special char, e.g. \\t\r\n"
                + "expire      a boolean representing whether to approve, expire and delete old HITs (only recommended if you're running in sandbox!). "
                + "backend     one of the following: mturk | local"
            );
            System.exit(-1);
        }

        // LOGGING
        try {
            txtHandler = new FileAppender(new PatternLayout("%d{dd MMM yyyy HH:mm:ss,SSS}\t%-5p [%t]: %m%n"), "logs/Runner.log");
            txtHandler.setAppend(true);
            LOGGER.addAppender(txtHandler);
        }
        catch (IOException io) {
            System.err.println(io.getMessage());
            System.exit(-1);
        }

        String file = args[0];
        String sep = args[1];
        BackendType backendType = BackendType.valueOf(args[3]);

        runAll(file, sep, backendType);
    }
}
