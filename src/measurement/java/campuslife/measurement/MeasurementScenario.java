package campuslife.measurement;

import java.nio.file.*;
import java.time.*;
import java.math.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic request plan and validation of the separate B.2-A SQL fixture. */
final class MeasurementScenario {
    static final ObjectMapper JSON=new ObjectMapper();
    enum Kind { DETAIL_HOT,DETAIL_NORMAL,CATEGORY,SEARCH,DEEP,ORDERS,CLAIM }
    record Task(int ordinal,Kind kind,long target,long student,String key) {
        String path(long category){return switch(kind){
            case DETAIL_HOT,DETAIL_NORMAL -> "/api/v1/shops/"+target;
            case CATEGORY -> "/api/v1/shops?categoryId="+category+"&page=1&size=20";
            case SEARCH -> "/api/v1/shops?keyword=%E7%A8%80%E6%9C%89&page=1&size=20";
            case DEEP -> "/api/v1/shops?page=20&size=20";
            case ORDERS -> "/api/v1/orders?page=1&size=20";
            case CLAIM -> "/api/v1/coupon-claims";
        };}
    }
    record Student(long id,int group,long total,List<Long> orders) {}
    record Rating(long count,BigDecimal average) {}
    static final class Invalid extends RuntimeException {
        Invalid(String code){super(code);}
    }
    static void need(boolean v,String code){if(!v)throw new Invalid(code);}
    static long number(JsonNode n){need(n!=null&&n.isIntegralNumber()&&n.canConvertToLong(),"INVALID_NUMBER");return n.longValue();}
    static String text(JsonNode n){need(n!=null&&n.isTextual(),"INVALID_TEXT");return n.textValue();}
    static long id(JsonNode n){String s=text(n);need(s.matches("[1-9][0-9]{0,18}"),"INVALID_ID");try{return Long.parseLong(s);}catch(NumberFormatException e){throw new Invalid("INVALID_ID");}}
    static Instant instant(JsonNode n){try{return Instant.parse(text(n));}catch(Exception e){throw new Invalid("INVALID_TIME");}}
    static void nil(JsonNode n){need(n!=null&&n.isNull(),"EXPECTED_NULL");}
    final String caseId,profile;
    final long haircutCategory,printCategory;
    final Map<Long,Student> students=new LinkedHashMap<>();
    final Map<Long,Rating> ratings=new HashMap<>();
    final List<Long> visible=new ArrayList<>(),details=new ArrayList<>(),campaigns=new ArrayList<>();
    MeasurementScenario(Path file,long haircutCategory,long printCategory)throws Exception {
        this(JSON.readTree(Files.readString(file)),haircutCategory,printCategory);
    }
    MeasurementScenario(JsonNode data,long haircutCategory,long printCategory){
        this.haircutCategory=haircutCategory;this.printCategory=printCategory;
        need(haircutCategory>0&&printCategory>0&&haircutCategory!=printCategory,"CATEGORY_INPUT");
        caseId=text(data.get("caseId"));profile=text(data.get("profile"));
        need(caseId.matches("case-[a-f0-9]{12,32}")&&Set.of("FORMAL","PILOT").contains(profile),"FIXTURE_IDENTITY");
        need(data.path("syntheticSqlHistory").asBoolean()&&!data.path("businessJourneyExecuted").asBoolean()
             &&"ASYNC/PAUSED".equals(data.path("gateState").asText()),"FIXTURE_PROVENANCE");
        need(number(data.path("counts").get("service_order"))==11000,"FIXTURE_SCALE");
        for(JsonNode s:data.withArray("students")){
            long account=number(s.get("id"));int group=(int)number(s.get("group"));List<Long> ids=new ArrayList<>();
            for(JsonNode o:s.withArray("orderIds"))ids.add(number(o));
            need(account>=900001&&account<=900180&&group==(account-900001)/60+1&&ids.size()==20
                 &&number(s.get("total"))>=20&&new HashSet<>(ids).size()==20,"STUDENT_EXPECTATION");
            need(students.put(account,new Student(account,group,number(s.get("total")),List.copyOf(ids)))==null,"DUPLICATE_STUDENT");
        }
        for(JsonNode s:data.withArray("publicShopIds"))visible.add(number(s));
        List<Long> distribution=new ArrayList<>();for(int n=1;n<=1000;n++)if(n%10==0||n%10>=5)distribution.add(1000000L+n);
        need(students.size()==180&&visible.equals(distribution),"FIXTURE_DISTRIBUTION");
        for(JsonNode d:data.withArray("detailShopIds"))details.add(number(d));
        need(details.equals(List.of(1000006L,1000010L,1000016L,1000020L,1000026L,1000030L)),"DETAIL_TARGETS");
        for(JsonNode r:data.withArray("ratings")){
            long shop=number(r.get("shop_id")),count=number(r.get("review_count")),sum=number(r.get("rating_sum"));
            need(count>0&&sum>=count&&sum<=5*count,"RATING_EXPECTATION");
            BigDecimal avg=BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);
            need(r.get("rating_average").isNumber()&&avg.compareTo(r.get("rating_average").decimalValue())==0,"RATING_ROUNDING");
            need(ratings.put(shop,new Rating(count,avg))==null,"DUPLICATE_RATING");
        }
        for(JsonNode c:data.withArray("campaigns")){
            campaigns.add(number(c.get("id")));need(number(c.get("total_quantity"))==(profile.equals("FORMAL")?20:2),"CAMPAIGN_STOCK");
        }
        need(ratings.size()==500&&campaigns.size()==(profile.equals("FORMAL")?3:1),"FIXTURE_COUNTS");
        for(int i=0;i<campaigns.size();i++)need(campaigns.get(i)==2000001L+i,"CAMPAIGN_IDS");
    }
    List<Task> plan(int segment,long seed){
        need(segment>=0&&segment<campaigns.size(),"SEGMENT");int factor=profile.equals("FORMAL")?10:1;
        List<Task> tasks=new ArrayList<>();int first=segment*60;
        for(int n=0;n<18*factor;n++)tasks.add(new Task(tasks.size(),Kind.DETAIL_HOT,details.getFirst(),0,""));
        for(int n=0;n<18*factor;n++)tasks.add(new Task(tasks.size(),Kind.DETAIL_NORMAL,details.get(1+n%5),0,""));
        for(int n=0;n<6*factor;n++)tasks.add(new Task(tasks.size(),Kind.CATEGORY,0,0,""));
        for(int n=0;n<3*factor;n++)tasks.add(new Task(tasks.size(),Kind.SEARCH,0,0,""));
        for(int n=0;n<3*factor;n++)tasks.add(new Task(tasks.size(),Kind.DEEP,0,0,""));
        for(int n=0;n<6*factor;n++)tasks.add(new Task(tasks.size(),Kind.ORDERS,0,900001+first+n,""));
        for(int n=0;n<6*factor;n++)tasks.add(new Task(tasks.size(),Kind.CLAIM,campaigns.get(segment),900001+first+n,
            "m062_"+caseId.substring(5)+"_"+segment+"_"+n));
        Collections.shuffle(tasks,new Random(seed));return List.copyOf(tasks);
    }
    List<Task> firstAccess(){List<Task> a=new ArrayList<>();for(int n=0;n<6;n++)a.add(new Task(n,n==0?Kind.DETAIL_HOT:Kind.DETAIL_NORMAL,details.get(n),0,""));return List.copyOf(a);}
    List<Task> warmup(int segment){
        need(segment>=0&&segment<campaigns.size(),"SEGMENT");List<Task> a=new ArrayList<>();
        for(int n=0;n<60;n++)a.add(new Task(a.size(),n%2==0?Kind.DETAIL_HOT:Kind.DETAIL_NORMAL,details.get(n%2==0?0:1+(n/2)%5),0,""));
        for(int n=0;n<20;n++)a.add(new Task(a.size(),Kind.CATEGORY,0,0,""));
        for(int n=0;n<20;n++)a.add(new Task(a.size(),Kind.ORDERS,0,900001+segment*60+n,""));return List.copyOf(a);
    }
    List<Long> pageIds(Kind kind){return switch(kind){
        case CATEGORY -> visible.stream().filter(n->n%2==0).limit(20).toList();
        case SEARCH -> List.of(1000997L);
        case DEEP -> visible.subList(380,400);
        default -> throw new Invalid("NOT_A_SHOP_PAGE");
    };}
    void page(JsonNode d,long total,int page){need(number(d.get("total"))==total&&number(d.get("page"))==page&&number(d.get("size"))==20
            &&number(d.get("totalPages"))==(total+19)/20,"PAGE_METADATA");}
    void shop(JsonNode d,long expected,Instant sent,Instant finished){
        need(id(d.get("id"))==expected&&visible.contains(expected),"SHOP_IDENTITY");
        long n=expected-1000000;boolean haircut=n%2==0,enabled=n%3!=0;
        need(text(d.get("name")).equals((n%997==0?"稀有":"校园")+(haircut?"理发":"打印")+"模拟店_"+String.format(Locale.ROOT,"%05d",n)),"SHOP_NAME");
        JsonNode c=d.get("category");need(id(c.get("id"))==(haircut?haircutCategory:printCategory)
                &&text(c.get("code")).equals(haircut?"HAIRCUT":"PRINT"),"SHOP_CATEGORY");
        need(d.get("businessEnabled").isBoolean()&&d.get("businessEnabled").booleanValue()==enabled&&d.get("openNow").isBoolean(),"SHOP_BUSINESS");
        boolean a=open(sent)&&enabled,b=open(finished)&&enabled;boolean actual=d.get("openNow").booleanValue();need(actual==a||actual==b,"OPEN_NOW");
        Rating rating=ratings.get(expected);need(number(d.get("reviewCount"))==(rating==null?0:rating.count),"REVIEW_COUNT");
        if(rating==null)nil(d.get("rating"));else need(d.get("rating").isNumber()&&d.get("rating").decimalValue().compareTo(rating.average)==0,"RATING_VALUE");
    }
    static boolean open(Instant at){LocalTime t=at.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();return !t.isBefore(LocalTime.of(8,0))&&t.isBefore(LocalTime.of(22,0));}
    void validateRead(Task t,JsonNode d,Instant sent,Instant finished){
        switch(t.kind){
            case DETAIL_HOT,DETAIL_NORMAL -> {
                shop(d,t.target,sent,finished);need(text(d.get("campusCode")).equals("XINGHAI_MAIN"),"CAMPUS");
                need(d.withArray("businessHours").size()==7,"HOUR_COUNT");Set<Long> days=new HashSet<>();
                for(JsonNode h:d.withArray("businessHours")){days.add(number(h.get("weekday")));need(text(h.get("startTime")).equals("08:00")&&text(h.get("endTime")).equals("22:00"),"HOURS");}
                need(days.equals(Set.of(1L,2L,3L,4L,5L,6L,7L)),"HOUR_DAYS");
            }
            case CATEGORY,SEARCH,DEEP -> {
                page(d,t.kind==Kind.CATEGORY?300:t.kind==Kind.SEARCH?1:600,t.kind==Kind.DEEP?20:1);
                List<Long> expected=pageIds(t.kind);need(d.withArray("items").size()==expected.size(),"SHOP_PAGE_SIZE");
                for(int n=0;n<expected.size();n++)shop(d.withArray("items").get(n),expected.get(n),sent,finished);
            }
            case ORDERS -> {
                Student student=students.get(t.student);need(student!=null,"STUDENT_ID");page(d,student.total,1);
                need(d.withArray("items").size()==20,"ORDER_PAGE_SIZE");
                for(int n=0;n<20;n++){
                    JsonNode o=d.withArray("items").get(n);long expected=student.orders.get(n),shop=expected/10000,sequence=expected%10000;
                    Instant start=LocalDate.of(2025,1,1).plusDays(sequence/20).atTime(1,0).toInstant(ZoneOffset.UTC).plusSeconds(sequence%20*1800);
                    need(id(o.get("id"))==expected&&id(o.get("shopId"))==shop&&text(o.get("orderNo")).equals("BL"+shop+"_"+sequence),"ORDER_IDENTITY");
                    need(text(o.get("status")).equals("COMPLETED")&&text(o.get("paymentStatus")).equals("PAID")&&text(o.get("orderType")).equals("BOOKING")
                        &&number(o.get("payableAmount"))==3000&&text(o.get("currency")).equals("CNY"),"ORDER_SUMMARY");
                    need(text(o.get("serviceName")).equals("模拟理发")&&text(o.get("shopName")).equals("校园理发模拟店_"+String.format(Locale.ROOT,"%05d",shop-1000000)),"ORDER_SNAPSHOT");
                    need(instant(o.get("createdAt")).equals(start.minusSeconds(10800))&&instant(o.get("bookingStartAt")).equals(start)
                         &&instant(o.get("bookingEndAt")).equals(start.plusSeconds(1800)),"ORDER_TIME");nil(o.get("printingProgress"));
                }
            }
            default -> throw new Invalid("NOT_A_READ");
        }
    }
}
