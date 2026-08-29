import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

final class BannerGenerator {
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x11, 0x14, 0x1a));
        g.fillRoundRect(0, 0, 320, 180, 24, 24);
        g.setColor(new Color(0xff, 0x4b, 0x55));
        g.fill(new RoundRectangle2D.Double(24, 42, 134, 96, 24, 24));
        g.setColor(Color.WHITE);
        Path2D play = new Path2D.Double();
        play.moveTo(72, 64);
        play.lineTo(119, 90);
        play.lineTo(72, 116);
        play.closePath();
        g.fill(play);
        g.fillRoundRect(178, 68, 116, 11, 6, 6);
        g.fillRoundRect(178, 87, 92, 11, 6, 6);
        g.fillRoundRect(178, 106, 105, 11, 6, 6);
        g.dispose();
        ImageIO.write(image, "png", new File(args[0]));
    }
}
