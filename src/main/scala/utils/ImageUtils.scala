package utils

import java.awt.image.BufferedImage
import java.awt.{Graphics2D, RenderingHints}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import javax.imageio.stream.MemoryCacheImageOutputStream

object ImageUtils {
  private val MaxDimension: Int = 1600
  private val LargeBytesThreshold: Int = 2 * 1024 * 1024 // 2MB

  /**
    * Prepare image bytes for vision model:
    * - Downscale large images to a max dimension
    * - Re-encode PNGs to JPEG if very large
    * - Return (preparedBytes, mimeType, detailHint)
    */
  def prepareImageForVision(original: Array[Byte], originalType: String): (Array[Byte], String, String) = {
    val detailDefaultHigh = "high"
    val detailLow = "low"

    val in = new ByteArrayInputStream(original)
    val img = ImageIO.read(in)
    if (img == null) {
      // Not an image or unsupported; fall back to original
      val mime = mimeFromType(originalType)
      val detail = if (original.length > LargeBytesThreshold) detailLow else detailDefaultHigh
      return (original, mime, detail)
    }

    val (scaledImage, scaled) = scaleIfNeeded(img, MaxDimension)

    // If original is big PNG or we scaled, consider JPEG re-encode to reduce size
    val shouldConvertToJpeg = originalType == "png" || original.length > LargeBytesThreshold || scaled

    if (shouldConvertToJpeg) {
      val jpegBytes = writeJpeg(scaledImage, 0.85f)
      val detail = if (jpegBytes.length > LargeBytesThreshold) detailLow else detailDefaultHigh
      (jpegBytes, "image/jpeg", detail)
    } else {
      // Keep original encoding; if scaled, re-encode as PNG
      val bytes = if (scaled) writePng(scaledImage) else original
      val mime = mimeFromType(originalType)
      val detail = if (bytes.length > LargeBytesThreshold) detailLow else detailDefaultHigh
      (bytes, mime, detail)
    }
  }

  private def scaleIfNeeded(img: BufferedImage, maxDim: Int): (BufferedImage, Boolean) = {
    val w = img.getWidth
    val h = img.getHeight
    val maxSide = Math.max(w, h)
    if (maxSide <= maxDim) return (img, false)

    val scale = maxDim.toDouble / maxSide.toDouble
    val newW = Math.max(1, Math.round(w * scale).toInt)
    val newH = Math.max(1, Math.round(h * scale).toInt)
    val resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
    val g: Graphics2D = resized.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.drawImage(img, 0, 0, newW, newH, null)
    } finally {
      g.dispose()
    }
    (resized, true)
  }

  private def writeJpeg(img: BufferedImage, quality: Float): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    if (!writers.hasNext) {
      // Fallback to default ImageIO write
      ImageIO.write(img, "jpg", baos)
      return baos.toByteArray
    }
    val writer = writers.next()
    val ios = new MemoryCacheImageOutputStream(baos)
    writer.setOutput(ios)
    val params = writer.getDefaultWriteParam
    if (params.canWriteCompressed) {
      params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
      params.setCompressionQuality(quality)
    }
    writer.write(null, new IIOImage(img, null, null), params)
    writer.dispose()
    ios.close()
    baos.toByteArray
  }

  private def writePng(img: BufferedImage): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    baos.toByteArray
  }

  private def mimeFromType(t: String): String = t match {
    case "png" => "image/png"
    case "jpeg" | "jpg" => "image/jpeg"
    case _ => "image/jpeg"
  }
}

