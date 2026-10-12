package soke.musicdelay.client.speaker.audio;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in session recorder. Producers never wait for disk or for queue space. */
public final class SpeakerDiagnostics {
    private static volatile Session session;
    private static volatile String status="Запись выключена";
    private static volatile Path lastFile;
    private SpeakerDiagnostics() { }
    private static final class Session {
        final ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(2048);
        final AtomicLong dropped=new AtomicLong();
        final long start=System.nanoTime(),maxMillis,maxBytes;
        volatile boolean accepting=true;
        Session(long millis,long bytes){maxMillis=millis;maxBytes=bytes;}
    }
    public static boolean recording(){var s=session;return s!=null&&s.accepting;}
    public static boolean busy(){return session!=null;}
    public static String status(){return status;}
    public static Path lastFile(){return lastFile;}
    public static void runtimeSample(){
        if(!recording())return;
        long collections=0,millis=0;
        for(var bean:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()){
            collections+=Math.max(0,bean.getCollectionCount());millis+=Math.max(0,bean.getCollectionTime());
        }
        event("runtime","gcCount="+collections+" gcMillis="+millis);
    }
    public static void start(Path folder){start(folder,600_000,16*1024*1024);}
    public static synchronized void start(Path folder,long millis,long bytes){
        if(session!=null)return;
        if(millis<=0 || bytes<4096)throw new IllegalArgumentException();
        var s=new Session(millis,bytes);session=s;status="Запись идёт";
        var thread=new Thread(()->write(s,folder),"always-play-diagnostic-writer");thread.setDaemon(true);thread.start();
    }
    public static void stop(){var s=session;if(s!=null){s.accepting=false;status="Сохранение отчёта…";}}
    public static void event(String kind,String detail){
        var s=session;if(s==null||!s.accepting)return;
        String text=(System.nanoTime()-s.start)/1_000_000+"ms "+kind+" "+detail;
        text=text.replace('\n',' ').replace('\r',' ');
        if(text.length()>2048)text=text.substring(0,2048);
        if(!s.queue.offer(text))s.dropped.incrementAndGet();
    }
    private static void write(Session s,Path folder){
        String reason="manual";
        try {
            Files.createDirectories(folder);
            String name="audio-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+"-"+java.util.UUID.randomUUID().toString().substring(0,8)+".log";
            Path file=folder.resolve(name);lastFile=file;
            try(var out=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
                String header="Always Play audio diagnostic v1 / patch 38\nstart="+Instant.now()+"\njava="+System.getProperty("java.version")+" os="+System.getProperty("os.name")+" cpus="+Runtime.getRuntime().availableProcessors()+" maxHeap="+Runtime.getRuntime().maxMemory()+"\nNo audio samples or track paths recorded.\n";
                out.write(header);out.flush();long size=header.getBytes(StandardCharsets.UTF_8).length,lastFlush=System.nanoTime();
                while(s.accepting||!s.queue.isEmpty()){
                    if((System.nanoTime()-s.start)/1_000_000>=s.maxMillis){s.accepting=false;reason="time-limit";}
                    String line=s.queue.poll(200,TimeUnit.MILLISECONDS);
                    if(line!=null){
                        int length=line.getBytes(StandardCharsets.UTF_8).length+1;
                        if(size+length+256>s.maxBytes){s.accepting=false;reason="size-limit";s.dropped.addAndGet(s.queue.size()+1);s.queue.clear();break;}
                        out.write(line);out.write('\n');size+=length;
                    }
                    if(System.nanoTime()-lastFlush>1_000_000_000L){out.flush();lastFlush=System.nanoTime();}
                }
                out.write("END reason="+reason+" dropped="+s.dropped.get()+"\n");
            }
            status="Сохранён: "+file.getFileName();
        }catch(Exception e){status="Ошибка записи: "+e.getClass().getSimpleName();}
        finally{s.accepting=false;synchronized(SpeakerDiagnostics.class){if(session==s)session=null;}}
    }
}

