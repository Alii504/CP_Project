// File: src/com/myteam/filter/ImageProcessor.java
package com.myteam.filter;

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

public class ImageProcessor {
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

    public ImageProcessor(ImageFilter filter,
                          ImageFilter.FilterType type,
                          int kernel) {
        this.filter   = filter;
        this.type     = type;
        this.kernel   = kernel;
        this.osBean   = (OperatingSystemMXBean)
          ManagementFactory.getOperatingSystemMXBean();
        this.memBean  = ManagementFactory.getMemoryMXBean();
    }

    public TimingResult process(String inPath,
                                String outSeqPath,
                                String outParPath) throws Exception {
        BufferedImage in = ImageIO.read(new File(inPath));
        if (in == null) throw new IllegalArgumentException("Cannot read: " + inPath);

        new File(outSeqPath).getParentFile().mkdirs();
        new File(outParPath).getParentFile().mkdirs();

        // warm-up
        filter.sequentialGaussian(in,kernel);
        filter.parallelGaussian  (in,kernel);
        filter.sequentialGrayscale(in);
        filter.parallelGrayscale  (in);
        filter.sequentialEdge     (in);
        filter.parallelEdge       (in);

        // ── SEQUENTIAL ─────────────────────────────────────────────
        CpuSampler cpuSeq = new CpuSampler(); cpuSeq.start();
        MemSampler memSeq = new MemSampler(); memSeq.start();
        long t0 = System.nanoTime();

        BufferedImage seqImg;
        switch(type){
          case GAUSSIAN:  seqImg = filter.sequentialGaussian(in,kernel); break;
          case GRAYSCALE: seqImg = filter.sequentialGrayscale(in);      break;
          case EDGE:      seqImg = filter.sequentialEdge(in);           break;
          default: throw new AssertionError();
        }

        long t1 = System.nanoTime();
        cpuSeq.stop();
        memSeq.stop();
        ImageIO.write(seqImg,"png",new File(outSeqPath));

        double seqSec       = (t1-t0)/1e9;
        double seqCpuMaxPct = cpuSeq.getMaxLoad()*100.0;
        MemoryUsage h0 = memBean.getHeapMemoryUsage(),
                    nh0= memBean.getNonHeapMemoryUsage();
        double seqRamMB   = (h0.getUsed()+nh0.getUsed())/1024.0/1024.0;

        // ── PARALLEL ───────────────────────────────────────────────
        CpuSampler cpuPar = new CpuSampler(); cpuPar.start();
        MemSampler memPar = new MemSampler(); memPar.start();
        t0 = System.nanoTime();

        BufferedImage parImg;
        switch(type){
          case GAUSSIAN:  parImg = filter.parallelGaussian(in,kernel); break;
          case GRAYSCALE: parImg = filter.parallelGrayscale(in);      break;
          case EDGE:      parImg = filter.parallelEdge(in);           break;
          default: throw new AssertionError();
        }

        t1 = System.nanoTime();
        cpuPar.stop();
        memPar.stop();
        ImageIO.write(parImg,"png",new File(outParPath));

        double parSec       = (t1-t0)/1e9;
        double parCpuMaxPct = cpuPar.getMaxLoad()*100.0;
        MemoryUsage h1 = memBean.getHeapMemoryUsage(),
                    nh1= memBean.getNonHeapMemoryUsage();
        double parRamMB   = (h1.getUsed()+nh1.getUsed())/1024.0/1024.0;

        return new TimingResult(
          seqSec, parSec,
          seqCpuMaxPct, parCpuMaxPct,
          seqRamMB, parRamMB
        );
    }

    /** True‐peak CPU sampler via getProcessCpuLoad() every 50 ms. */
    private static class CpuSampler {
        private final OperatingSystemMXBean osBean =
          (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        private final ScheduledExecutorService exec =
          Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"cpu-sampler"); t.setDaemon(true); return t;
          });
        private volatile double maxLoad = 0;

        public void start() {
            maxLoad = 0;
            exec.scheduleAtFixedRate(() -> {
                double l = osBean.getProcessCpuLoad();
                if (l > maxLoad) maxLoad = Math.min(l,1.0);
            }, 0, 10, TimeUnit.MILLISECONDS);
        }
        public void stop() { exec.shutdownNow(); }
        public double getMaxLoad() { return maxLoad; }
    }

    /** Peak‐RAM sampler every 50 ms. */
    private static class MemSampler {
        private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        private final ScheduledExecutorService exec =
          Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"mem-sampler"); t.setDaemon(true); return t;
          });
        private volatile long maxUsed = 0;

        public void start() {
            maxUsed = 0;
            exec.scheduleAtFixedRate(() -> {
                MemoryUsage h  = memBean.getHeapMemoryUsage();
                MemoryUsage nh = memBean.getNonHeapMemoryUsage();
                long used = h.getUsed() + nh.getUsed();
                if (used > maxUsed) maxUsed = used;
            }, 0, 50, TimeUnit.MILLISECONDS);
        }
        public void stop() { exec.shutdownNow(); }
        public double getMaxUsedMB() { return maxUsed/1024.0/1024.0; }
    }
}
