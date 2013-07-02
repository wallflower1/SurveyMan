package survey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import utils.Gensym;

public class SurveyResponse {
    public static final Gensym gensym = new Gensym("sr");
    public final String srid = gensym.next();
    // this gets filled out in surveyposter.parse
    List<QuestionResponse> responses = new ArrayList<QuestionResponse>();
    
    public SurveyResponse randomResponse(Survey s){
        Random r = new Random();
        SurveyResponse sr = new SurveyResponse();
        for(Question q: s.questions){
            QuestionResponse qr = new QuestionResponse();
            String[] keys = q.options.keySet().toArray(new String[0]);
            int randIndex=r.nextInt(keys.length);
            qr.quid=q.quid;
            qr.oids.add(q.options.get(keys[randIndex]).oid);
            sr.responses.add(qr);
        }
        return sr;
    }
    
    public SurveyResponse consistentResponse(Survey s){
        SurveyResponse sr = new SurveyResponse();
        for(Question q: s.questions){
            QuestionResponse qr = new QuestionResponse();
            String[] keys = q.options.keySet().toArray(new String[0]);
            qr.quid=q.quid;
            qr.oids.add(q.options.get(keys[0]).oid);
            sr.responses.add(qr);
        }
        return sr;
    }

    /** otherValues is a map of the key value pairs that are not necessary for QC,
     *  but are returned by the service. They should be pushed through the system
     *  and spit into an output file, unaltered.
     */
    Map<String, String> otherValues = new HashMap<String, String>();
}

class QuestionResponse {

    public String sid;
    public String quid;
    public List<String> oids;
    public int indexSeen; // the index at which this question was seen.

    /** otherValues is a map of the key value pairs that are not necessary for QC,
     *  but are returned by the service. They should be pushed through the system
     *  and spit into an output file, unaltered.
     */
    Map<String, String> otherValues = new HashMap<String, String>();

    public static void main(String[] args){
        // write test code here
    }
}