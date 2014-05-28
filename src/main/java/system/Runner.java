package system;

import com.amazonaws.mturk.service.exception.AccessKeyException;
import com.amazonaws.mturk.service.exception.InsufficientFundsException;
import input.csv.CSVLexer;
import input.csv.CSVParser;
import interstitial.*;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.dom4j.DocumentException;
import survey.Survey;
import survey.exceptions.SurveyException;
import system.localhost.LocalLibrary;
import system.localhost.LocalResponseManager;
import system.localhost.LocalSurveyPoster;
import system.localhost.Server;
import system.localhost.server.WebServerException;
import system.mturk.MturkLibrary;
import system.mturk.MturkResponseManager;
import system.mturk.MturkSurveyPoster;
import util.ArgReader;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Runner {

    // everything that uses MturkResponseManager should probably use some parameterized type to make this more general
    // I'm hard-coding in the mturk stuff for now though.
    public static final Logger LOGGER = Logger.getLogger("SurveyMan");
    private static long timeSinceLastNotice = System.currentTimeMillis();
    private static FileAppender txtHandler;
    private static int totalHITsGenerated;
    public static BackendType backendType;
    public static AbstractResponseManager responseManager;
    public static ISurveyPoster surveyPoster;
    public static Library library;

    public static ArgumentParser makeArgParser(){
        // move more of the setup into this method
        ArgumentParser argumentParser = ArgumentParsers.newArgumentParser(Runner.class.getName(),true,"-").description("Posts surveys");
        argumentParser.addArgument("survey").required(true);
        for (Map.Entry<String, String> entry : ArgReader.getMandatoryAndDefault(Runner.class).entrySet()) {
            String arg = entry.getKey();
            //System.out.println("mandatory:"+arg);
            Argument a = argumentParser.addArgument("--" + arg)
                    .required(true)
                    .help(ArgReader.getDescription(arg));
            String[] c = ArgReader.getChoices(arg);
            if (c.length>0)
                a.choices(c);

        }
        for (Map.Entry<String, String> entry : ArgReader.getOptionalAndDefault(Runner.class).entrySet()){
            String arg = entry.getKey();
            //System.out.println("optional:"+arg);
            Argument a = argumentParser.addArgument("--" + arg)
                    .required(false)
                    .setDefault(entry.getValue())
                    .help(ArgReader.getDescription(arg));
            String[] c = ArgReader.getChoices(arg);
            if (c.length>0)
                a.choices(c);
        }
        return argumentParser;
    }

    public static void init(String bt, String properties, String config) throws IOException {
        // if it's an unrecognized backend type, it will fail earlier
        backendType = BackendType.valueOf(bt);
        switch (backendType) {
            case LOCALHOST:
                responseManager = new LocalResponseManager();
                surveyPoster = new LocalSurveyPoster();
                library = new LocalLibrary();
                library.init();
                if (properties!=null)
                    library.updateProperties(properties);
                break;
            case MTURK:
                library = new MturkLibrary();
                library.init();
                if (config!=null)
                    ((MturkLibrary) library).CONFIG = config;
                if (properties!=null)
                    library.updateProperties(properties);
                responseManager = new MturkResponseManager((MturkLibrary) library);
                surveyPoster = new MturkSurveyPoster();
                surveyPoster.init(config);
                break;
        }
    }

    public static void init(String bt) throws IOException{
        init(bt, "","");
    }

    public static void init(BackendType bt) throws IOException{
        init(bt.name());
    }

    public static int recordAllTasksForSurvey(Survey survey, BackendType backendType) throws IOException, SurveyException, DocumentException {

        Record record = MturkResponseManager.getRecord(survey);
        int allHITs = record.getAllTasks().length;
        String hiturl = "", msg;
        int responsesAdded = 0;

        for (ITask hit : record.getAllTasks()) {
            hiturl = surveyPoster.makeTaskURL(hit);
            responsesAdded = responseManager.addResponses(survey, hit);
        }

        msg = String.format("Polling for responses for Tasks at %s (%d total)"
                , hiturl
                , record.responses.size());

        if (System.currentTimeMillis() - timeSinceLastNotice > 90000) {
            System.out.println(msg);
            LOGGER.info(msg);
            timeSinceLastNotice = System.currentTimeMillis();
        }

        if (allHITs > totalHITsGenerated) {
            System.out.println("total Tasks generated: "+record.getAllTasks().length);
            totalHITsGenerated = allHITs;
            System.out.println(msg);
        }

        return responsesAdded;
    }

    public static Thread makeResponseGetter(final Survey survey, final BoxedBool interrupt, final BackendType backendType){
        // grab responses for each incomplete survey in the responsemanager
        return new Thread(){
            @Override
            public void run(){
                int waittime = 1;
                while (!interrupt.getInterrupt()){
                    System.out.println(String.format("Checking for responses in %s", backendType));
                    while(!interrupt.getInterrupt()){
                        try {
                            int n = recordAllTasksForSurvey(survey, backendType);
                            if (n > 0)
                                waittime = 2;
                        } catch (IOException e) {
                            e.printStackTrace(); throw new RuntimeException(e);
                        } catch (SurveyException e) {
                            e.printStackTrace(); throw new RuntimeException(e);
                        } catch (DocumentException e) {
                            e.printStackTrace(); throw new RuntimeException(e);
                        }
                        AbstractResponseManager.chill(waittime);
                        if (waittime > AbstractResponseManager.maxwaittime)
                            waittime = 1;
                        else waittime *= 2;
                    }
                    // if we're out of the loop, expire and process the remaining HITs
                    try {
                        Record record = AbstractResponseManager.getRecord(survey);
                        ITask[] tasks = record.getAllTasks();
                        System.out.println("\n\tDANGER ZONE\n");
                        for (ITask task : tasks){
                            boolean expiredAndAdded = false;
                            while (! expiredAndAdded) {
                                try {
                                    responseManager.makeTaskUnavailable(task);
                                    responseManager.addResponses(survey, task);
                                    expiredAndAdded = true;
                                } catch (Exception e) {
                                    System.err.println("something in the response getter thread threw an error.");
                                    e.printStackTrace();
                                    AbstractResponseManager.chill(1);
                                }
                            }
                        }
                        AbstractResponseManager.removeRecord(record);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (SurveyException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    }

    public static boolean stillLive(Survey survey) throws IOException, SurveyException, ClassNotFoundException, IllegalAccessException, InstantiationException {
        Record record = AbstractResponseManager.getRecord(survey);
        if (record==null) {
            return false;
        }
        boolean done = record.qc.complete(record.responses, record.library.props);
        return ! done;
    }

    public static void writeResponses(Survey survey, Record record){
        for (ISurveyResponse sr : record.responses) {
            if (!sr.isRecorded()) {
                BufferedWriter bw = null;
                System.out.println("writing "+sr.srid());
                try {
                    System.out.println(record.outputFileName);
                    File f = new File(record.outputFileName);
                    bw = new BufferedWriter(new FileWriter(f, true));
                    if (! f.exists() || f.length()==0)
                        bw.write(ResponseWriter.outputHeaders(survey));
                    String txt = ResponseWriter.outputSurveyResponse(survey, sr);
                    System.out.println(txt);
                    bw.write(ResponseWriter.outputSurveyResponse(survey, sr));
                    sr.setRecorded(true);
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

    public static Thread makeWriter(final Survey survey, final BoxedBool interrupt){
        //writes hits that correspond to current jobs in memory to their files
        return new Thread(){
            @Override
            public void run(){
                Record record = null;
                do {
                    try {
                        record = AbstractResponseManager.getRecord(survey);
                        writeResponses(survey, record);
                        AbstractResponseManager.chill(3);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (SurveyException e) {
                        e.printStackTrace();
                    }
                } while (!interrupt.getInterrupt());
                // clean up
                synchronized (record) {
                    System.out.print("Writing straggling data...");
                    writeResponses(survey, record);
                    AbstractResponseManager.putRecord(survey, null);
                    System.out.println("done.");
                }
            }
        };
    }

    public static void run(final Record record, final BoxedBool interrupt)
            throws SurveyException, IOException, ParseException, IllegalAccessException, InstantiationException, ClassNotFoundException {
        Survey survey = record.survey;
        // make sure survey is well formed
        do {
            if (!interrupt.getInterrupt()) {
                surveyPoster.postSurvey(responseManager, record);
                AbstractResponseManager.chill(2);
            }
            AbstractResponseManager.chill(2);
        } while (stillLive(survey));
        System.out.println("no longer live");
        interrupt.setInterrupt(true, "QC goal met");
        surveyPoster.stopSurvey(responseManager, record, interrupt);
    }

    public static void runAll(String s, String sep)
        throws InvocationTargetException, SurveyException, IllegalAccessException, NoSuchMethodException, IOException, ParseException, InterruptedException, ClassNotFoundException, InstantiationException {

        while (true) {
            try {
                final BoxedBool interrupt = new BoxedBool(false);
                CSVParser csvParser = new CSVParser(new CSVLexer(s, sep));
                Survey survey = csvParser.parse();
                // create and store the record
                final Record record = new Record(survey, library, backendType);
                AbstractResponseManager.putRecord(survey, record);
                Thread writer = makeWriter(survey, interrupt);
                Thread responder = makeResponseGetter(survey, interrupt, backendType);
                Thread runner = new Thread() {
                    @Override
                    public void run() {
                        try {
                            Runner.run(record, interrupt);
                        } catch (SurveyException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (ParseException e) {
                            e.printStackTrace();
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        } catch (InstantiationException e) {
                            e.printStackTrace();
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                };
                runner.start();
                writer.start();
                responder.start();
                System.out.println("Target number of valid responses: " + record.library.props.get("numparticipants"));
                //repl(interrupt, survey, record, backendType);
                runner.join();
                responder.join();
                writer.join();
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
            throws IOException, SurveyException, InterruptedException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, ParseException, WebServerException, InstantiationException, ClassNotFoundException {
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

        ArgumentParser argumentParser = makeArgParser();
        Namespace ns;
        try {
            ns = argumentParser.parseArgs(args);
            System.out.println(ns);

            init(ns.getString("backend"), ns.getString("properties"), ns.getString("config"));

            if (backendType.equals(BackendType.LOCALHOST))
                Server.startServe();

            runAll(ns.getString("survey"), ns.getString("separator"));

            if (backendType.equals(BackendType.LOCALHOST))
                Server.endServe();

        } catch (ArgumentParserException e) {
            System.err.println("FAILURE: "+e.getMessage());
            LOGGER.fatal(e);
            argumentParser.printHelp();
        }
    }
}
