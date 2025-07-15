// File: src/com/myteam/filter/VideoProcessor.java
package com.myteam.filter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.*;

public class VideoProcessor {
    public static class TimingResult {
        public final double seqSec, parSec;
        public final double seqCpuMaxPct, parCpuMaxPct;
        public final double seqRamMB, parRamMB;
        public TimingResult(double seqSec, double parSec,
                            double seqCpuMaxPct, double parCpuMaxPct,
                            double seqRamMB, double parRamMB) {
            this.seqSec       = seqSec;
            this.parSec       = parSec;
            this.seqCpuMaxPct = seqCpuMaxPct;
            this.parCpuMaxPct = parCpuMaxPct;
            this.seqRamMB     = seqRamMB;
            this.parRamMB     = parRamMB;
        }
        public double speedup() { return seqSec / parSec; }
    }

    private final ImageFilter filter;
    private final ImageFilter.FilterType type;
    private final int kernel;
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean      memBean;

    public VideoProcessor(ImageFilter filter,
                          ImageFilter.FilterType type,
                          int kernel) {
        this.filter = filter;
        this.type   = type;
        this.kernel = kernel;
        this.osBean = (OperatingSystemMXBean)
          ManagementFactory.getOperatingSystemMXBean();
        this.memBean= ManagementFactory.getMemoryMXBean();
    }

    public TimingResult process(String inPath,
                                String outSeqPath,
                                String outParPath) throws Exception {
        // 1) Grab metadata
        Java2DFrameConverter conv = new Java2DFrameConverter();
        FFmpegFrameGrabber p = new FFmpegFrameGrabber(inPath);
        p.start();
        int w = p.getImageWidth(), h = p.getImageHeight();
        double fps = p.getVideoFrameRate();
        p.stop();

        // Ensure dirs
        new File(outSeqPath).getParentFile().mkdirs();
        new File(outParPath).getParentFile().mkdirs();

        // ── SEQUENTIAL ───────────────────────────────────────────────
        CpuSampler cpuSeq = new CpuSampler(); cpuSeq.start();
        MemSampler memSeq = new MemSampler(); memSeq.start();
        long t0 = System.nanoTime();

        try (FFmpegFrameGrabber grab=new FFmpegFrameGrabber(inPath);
             FFmpegFrameRecorder rec=new FFmpegFrameRecorder(outSeqPath,w,h,0)) {
            grab.start();
            rec.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            rec.setFormat("mp4");
            rec.setFrameRate(fps);
            rec.start();
            Frame f;
            while ((f=grab.grabImage()) != null) {
                BufferedImage bi = conv.convert(f);
                BufferedImage out;
                switch(type){
                  case GAUSSIAN:  out=filter.sequentialGaussian(bi,kernel); break;
                  case GRAYSCALE: out=filter.sequentialGrayscale(bi);      break;
                  case EDGE:      out=filter.sequentialEdge(bi);           break;
                  default: throw new AssertionError();
                }
                rec.record(conv.convert(toBGR(out,w,h)));
            }
            rec.stop(); grab.stop();
        }

        long t1 = System.nanoTime();
        cpuSeq.stop(); memSeq.stop();
        double seqSec       = (t1-t0)/1e9;
        double seqCpuMaxPct = cpuSeq.getMaxLoad()*100.0;
        MemoryUsage sh = memBean.getHeapMemoryUsage(),
                    snh= memBean.getNonHeapMemoryUsage();
        double seqRamMB   = (sh.getUsed()+snh.getUsed())/1024.0/1024.0;

        // ── PARALLEL ────────────────────────────────────────────────
        CpuSampler cpuPar = new CpuSampler(); cpuPar.start();
        MemSampler memPar = new MemSampler(); memPar.start();
        t0 = System.nanoTime();

        try (FFmpegFrameGrabber grab=new FFmpegFrameGrabber(inPath);
             FFmpegFrameRecorder rec=new FFmpegFrameRecorder(outParPath,w,h,0)) {
            grab.start();
            rec.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            rec.setFormat("mp4");
            rec.setFrameRate(fps);
            rec.start();
            Frame f;
            while ((f=grab.grabImage()) != null) {
                BufferedImage bi = conv.convert(f);
                BufferedImage out;
                switch(type){
                  case GAUSSIAN:  out=filter.parallelGaussian(bi,kernel); break;
                  case GRAYSCALE: out=filter.parallelGrayscale(bi);      break;
                  case EDGE:      out=filter.parallelEdge(bi);           break;
                  default: throw new AssertionError();
                }
                rec.record(conv.convert(toBGR(out,w,h)));
            }
            rec.stop(); grab.stop();
        }

        long t2 = System.nanoTime();
        cpuPar.stop(); memPar.stop();
        double parSec       = (t2-t0)/1e9;
        double parCpuMaxPct = cpuPar.getMaxLoad()*100.0;
        MemoryUsage ph = memBean.getHeapMemoryUsage(),
                    pnh= memBean.getNonHeapMemoryUsage();
        double parRamMB   = (ph.getUsed()+pnh.getUsed())/1024.0/1024.0;

        return new TimingResult(
          seqSec, parSec,
          seqCpuMaxPct, parCpuMaxPct,
          seqRamMB, parRamMB
        );
    }

    private BufferedImage toBGR(BufferedImage s,int w,int h){
        BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g=b.createGraphics();
        g.drawImage(s,0,0,null);
        g.dispose();
        return b;
    }

    /** True‐peak CPU sampler via getProcessCpuLoad() every 50 ms. */
    private static class CpuSampler {
        private final OperatingSystemMXBean osBean =
          (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        private final ScheduledExecutorService exec =
          Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"cpu-sampler");t.setDaemon(true);return t;
          });
        private volatile double maxLoad=0;

        public void start(){
          maxLoad=0;
          exec.scheduleAtFixedRate(()->{
            double l=osBean.getProcessCpuLoad();
            if(l>maxLoad) maxLoad=Math.min(l,0.99);
          },0,5,TimeUnit.MILLISECONDS);
        }
        public void stop(){ exec.shutdownNow(); }
        public double getMaxLoad(){ return maxLoad; }
    }

    /** Peak‐RAM sampler every 50 ms. */
    private static class MemSampler {
        private final MemoryMXBean memBean=ManagementFactory.getMemoryMXBean();
        private final ScheduledExecutorService exec=
          Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"mem-sampler");t.setDaemon(true);return t;
          });
        private volatile long maxUsed=0;

        public void start(){
          maxUsed=0;
          exec.scheduleAtFixedRate(()->{
            MemoryUsage h=memBean.getHeapMemoryUsage();
            MemoryUsage nh=memBean.getNonHeapMemoryUsage();
            long used=h.getUsed()+nh.getUsed();
            if(used>maxUsed) maxUsed=used;
          },0,5,TimeUnit.MILLISECONDS);
        }
        public void stop(){ exec.shutdownNow(); }
        public double getMaxUsedMB(){ return maxUsed/1024.0/1024.0; }
    }
}
