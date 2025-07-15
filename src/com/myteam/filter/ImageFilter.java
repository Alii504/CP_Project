// File: src/com/myteam/filter/ImageFilter.java
package com.myteam.filter;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

public class ImageFilter {
    public enum FilterType { GAUSSIAN, GRAYSCALE, EDGE }

    // ── Create a true Gaussian kernel with stronger blur ────────────────
    private float[][] createGaussianKernel(int k) {
        int r = k / 2;
        float sigma = k;         // increased sigma for "very very" blur
        float twoSigmaSq = 2 * sigma * sigma;
        float[][] kernel = new float[k][k];
        float sum = 0f;

        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                float weight = (float) Math.exp(-(x*x + y*y) / twoSigmaSq);
                kernel[y + r][x + r] = weight;
                sum += weight;
            }
        }
        // normalize
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                kernel[i][j] /= sum;
            }
        }
        return kernel;
    }

    // ── Gaussian blur, sequential with true Gaussian weights ───────────
    public BufferedImage sequentialGaussian(BufferedImage in, int k) {
        int w = in.getWidth(), h = in.getHeight(), r = k / 2;
        int[] inPix = in.getRGB(0, 0, w, h, null, 0, w), outPix = new int[w * h];
        float[][] kernel = createGaussianKernel(k);

        for (int y = 0; y < h; y++) {
            int yOff = y * w;
            for (int x = 0; x < w; x++) {
                float sr = 0, sg = 0, sb = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    int row = yy * w;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        float weight = kernel[dy + r][dx + r];
                        int rgb = inPix[row + xx];
                        sr += ((rgb >> 16) & 0xFF) * weight;
                        sg += ((rgb >> 8) & 0xFF) * weight;
                        sb += (rgb & 0xFF) * weight;
                    }
                }
                int ir = Math.min(255, Math.max(0, Math.round(sr)));
                int ig = Math.min(255, Math.max(0, Math.round(sg)));
                int ib = Math.min(255, Math.max(0, Math.round(sb)));
                outPix[yOff + x] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, outPix, 0, w);
        return out;
    }

    // ── Gaussian blur, parallel with true Gaussian weights ──────────────
    public BufferedImage parallelGaussian(BufferedImage in, int k) {
        int w = in.getWidth(), h = in.getHeight(), r = k / 2;
        int[] inPix = in.getRGB(0, 0, w, h, null, 0, w), outPix = new int[w * h];
        float[][] kernel = createGaussianKernel(k);

        IntStream.range(0, h).parallel().forEach(y -> {
            int yOff = y * w;
            for (int x = 0; x < w; x++) {
                float sr = 0, sg = 0, sb = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    int row = yy * w;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        float weight = kernel[dy + r][dx + r];
                        int rgb = inPix[row + xx];
                        sr += ((rgb >> 16) & 0xFF) * weight;
                        sg += ((rgb >> 8) & 0xFF) * weight;
                        sb += (rgb & 0xFF) * weight;
                    }
                }
                int ir = Math.min(255, Math.max(0, Math.round(sr)));
                int ig = Math.min(255, Math.max(0, Math.round(sg)));
                int ib = Math.min(255, Math.max(0, Math.round(sb)));
                outPix[yOff + x] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
            }
        });

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, outPix, 0, w);
        return out;
    }

    // ── Grayscale, sequential ───────────────────────────────────────────
    public BufferedImage sequentialGrayscale(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = in.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int gray = (r + g + b) / 3;
                out.setRGB(x, y, (0xFF << 24) | (gray << 16) | (gray << 8) | gray);
            }
        }
        return out;
    }

    // ── Grayscale, parallel ─────────────────────────────────────────────
    public BufferedImage parallelGrayscale(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int rgb = in.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int gray = (r + g + b) / 3;
                out.setRGB(x, y, (0xFF << 24) | (gray << 16) | (gray << 8) | gray);
            }
        });
        return out;
    }

    // ── Sobel edge kernels ─────────────────────────────────────────────
    private static final int[][] GX = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
    private static final int[][] GY = {{ 1, 2, 1}, { 0, 0, 0}, {-1,-2,-1}};

    // ── Edge, sequential ──────────────────────────────────────────────
    public BufferedImage sequentialEdge(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        int[] inPix = in.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sx = 0, sy = 0;
                for (int ky = -1; ky <= 1; ky++) {
                    int yy = Math.min(h - 1, Math.max(0, y + ky));
                    for (int kx = -1; kx <= 1; kx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + kx));
                        int rgb = inPix[yy * w + xx];
                        int lum = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                        sx += GX[ky + 1][kx + 1] * lum;
                        sy += GY[ky + 1][kx + 1] * lum;
                    }
                }
                int e = Math.min(255, (int) Math.hypot(sx, sy));
                out.setRGB(x, y, (0xFF << 24) | (e << 16) | (e << 8) | e);
            }
        }
        return out;
    }

    // ── Edge, parallel ───────────────────────────────────────────────
    public BufferedImage parallelEdge(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        int[] inPix = in.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sx = 0, sy = 0;
                for (int ky = -1; ky <= 1; ky++) {
                    int yy = Math.min(h - 1, Math.max(0, y + ky));
                    for (int kx = -1; kx <= 1; kx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + kx));
                        int rgb = inPix[yy * w + xx];
                        int lum = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                        sx += GX[ky + 1][kx + 1] * lum;
                        sy += GY[ky + 1][kx + 1] * lum;
                    }
                }
                int e = Math.min(255, (int) Math.hypot(sx, sy));
                out.setRGB(x, y, (0xFF << 24) | (e << 16) | (e << 8) | e);
            }
        });
        return out;
    }
}
