package campuslife.diagnostics;

/** Single bounded CPU worker. No Spring, networking, credentials or file access. */
public final class CpuWindow {
    public static void main(String[] args) {
        if(args.length!=1||!(args[0].equals("1")||args[0].equals("60")))throw new IllegalArgumentException("WINDOW");
        int seconds=Integer.parseInt(args[0]);long start=System.nanoTime(),end=start+seconds*1_000_000_000L,value=1,iterations=0;
        while(System.nanoTime()<end){for(int i=0;i<10000;i++)value=(value*6364136223846793005L+1442695040888963407L)^(value>>>17);iterations+=10000;}
        System.out.println("{\"requestedSeconds\":"+seconds+",\"elapsedNanos\":"+(System.nanoTime()-start)+",\"iterations\":"+iterations+",\"checksum\":"+value+"}");
    }
}
