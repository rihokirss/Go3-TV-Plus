import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

final class ImageScaler {
    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(new File(args[0]));
        int targetWidth = Integer.parseInt(args[2]);
        int targetHeight = Integer.parseInt(args[3]);
        double scale = Math.max(
            targetWidth / (double) source.getWidth(),
            targetHeight / (double) source.getHeight()
        );
        int width = (int) Math.ceil(source.getWidth() * scale);
        int height = (int) Math.ceil(source.getHeight() * scale);
        int x = (targetWidth - width) / 2;
        int y = (targetHeight - height) / 2;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, x, y, width, height, null);
        graphics.dispose();
        ImageIO.write(target, "png", new File(args[1]));
    }
}
