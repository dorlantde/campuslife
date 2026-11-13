package campuslife.measurement;
import java.util.*;
/** Pure host-to-driver fail-closed heartbeat checks; no network or database. */
public final class MeasurementRuntimeSignalChecks {
    public static void main(String[] args)throws Exception{
        String c="case-0123456789abcdef",id="a".repeat(32);long now=100000;
        Map<String,Object> state=new LinkedHashMap<>(Map.of("caseId",c,"instanceId",id,"baselinePassed",true,"stop",false,"validUntilUnixMillis",now+15000));
        int passed=0;
        if(!MeasurementRuntime.validHost(MeasurementScenario.JSON.valueToTree(state),c,id,now))throw new AssertionError();passed++;
        for(String key:List.of("caseId","instanceId","baselinePassed","stop","validUntilUnixMillis")){
            var changed=new LinkedHashMap<>(state);changed.remove(key);
            if(MeasurementRuntime.validHost(MeasurementScenario.JSON.valueToTree(changed),c,id,now))throw new AssertionError(key);passed++;
        }
        for(var change:List.of(Map.entry("caseId",(Object)"case-other"),Map.entry("instanceId",(Object)"b".repeat(32)),Map.entry("baselinePassed",(Object)false),Map.entry("stop",(Object)true),Map.entry("validUntilUnixMillis",(Object)(now-1)),Map.entry("validUntilUnixMillis",(Object)(now+20001)))){
            var changed=new LinkedHashMap<>(state);changed.put(change.getKey(),change.getValue());
            if(MeasurementRuntime.validHost(MeasurementScenario.JSON.valueToTree(changed),c,id,now))throw new AssertionError(change.getKey());passed++;
        }
        if(args.length==3){
            var scenario=new MeasurementScenario(java.nio.file.Path.of(args[0]),Long.parseLong(args[1]),Long.parseLong(args[2]));
            if(scenario.students.size()!=180||scenario.ratings.size()!=500||scenario.plan(0,6203).size()!=600)throw new AssertionError("ACTUAL_PERSISTED_FIXTURE");
            passed++;
        }
        System.out.println("{\"checks\":"+passed+",\"passed\":true}");
    }
}
