package net.koofr.play2sprites

import sbt._
import Keys._
import java.io.IOException
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

object GenerateSprites extends Plugin {

  val spritesSrcImages = SettingKey[PathFinder](
    "sprites-src-images",
    "source images for sprites"
  )
  
  val spritesDestImage = SettingKey[File](
    "sprites-dest-image",
    "destination sprite image file"
  )
  
  val spritesCssSpritePath = SettingKey[String](
    "sprites-css-sprite-path",
    "path to sprite image relative to css file"
  )
  
  val spritesCssClassPrefix = SettingKey[String](
    "sprites-css-class-prefix",
    "css class prefix"
  )
  
  val spritesDestCss = SettingKey[File](
    "sprites-dest-css",
    "destination css file"
  )
  
  val spritesGen = TaskKey[Seq[File]](
    "sprites-gen",
    "generate sprite from images"
  )
  
  val genSpritesSettings: Seq[Setting[_]] = Seq(
      
    spritesCssClassPrefix := "",
    
    spritesGen <<= (
      spritesSrcImages,
      spritesDestImage,
      spritesCssSpritePath,
      spritesCssClassPrefix,
      spritesDestCss,
      cacheDirectory,
      streams
    ) map { (srcImages, destImage, relPath, cssClassPrefix, css, cache, s) =>
      val files = srcImages.get.sortBy(_.getName)
      
      val cacheFile = cache / "sprites"
      val currentInfos = files.map(f => f -> FileInfo.lastModified(f)).toMap
      
      val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)
      
      if (!files.isEmpty && (previousInfo != currentInfos || !destImage.exists || !css.exists)) {
        val generated = generateSprites(files, destImage, relPath, cssClassPrefix, css, s)
        
        Sync.writeInfo(cacheFile,
          Relation.empty[File, File] ++ generated,
          currentInfos)(FileInfo.lastModified.format)
      }
      
      Seq()
    }
    
  )
  
  def generateSprites(files: Seq[File], destImage: File, relPath: String,
        cssClassPrefix: String, css: File, s: TaskStreams) = {
    
    s.log.info("Generating sprites for %d images" format(files.length))
    
    IO.createDirectory(destImage.getParentFile)
    IO.createDirectory(css.getParentFile)
    
    val eitherImages: Seq[(File, Either[Exception, BufferedImage])] = files.map { file =>
      try {
        file -> Option(ImageIO.read(file)).map(Right(_)).getOrElse(Left(new NullPointerException))
      } catch {
        case e: IOException => file -> Left(e)
      }
    }
    
    val images = eitherImages.collect { case (file, Right(img)) => file -> img }
    
    val errors = eitherImages.collect { case (file, Left(e)) => file -> e }
    
    errors map { case (file, error) =>
      s.log.error("Image %s could not be loaded: %s." format(file, error))
    }
    
    val width = images.map(_._2.getWidth).max
    val height = images.map(_._2.getHeight).sum
    
    val sprite = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    
    val processed = images.foldLeft((List[SpriteInfo](), 0)) { case ((processed, y), (file, img)) =>
      sprite.createGraphics().drawImage(img, 0, y, null)
      
      val cleanName = file.getName.toLowerCase.split("\\.").init.mkString("-").replace("_", "-")
    
      val cssClass = "." + cssClassPrefix + cleanName
    
      val info = SpriteInfo(file, img.getWidth, img.getHeight, y, cssClass)
    
      (info :: processed, y + info.height)
    }._1.reverse
    
    val written = ImageIO.write(sprite, "png", destImage)
    
    val cssClassBodies = processed.map { info =>
      val css = """|%s {
        |  background-position: 0 -%dpx;
        |  width: %dpx;
        |  height: %dpx;
        |}""".stripMargin
    
      css.format(info.cssClass, info.offsetY, info.width, info.height)
    }.mkString("\n\n")
    
    val cssOutputTpl = """|%s {
      |  background: url('%s') no-repeat;
      |}
      |
      |%s""".stripMargin
    
    val allCssClasses = processed.map(_.cssClass).mkString(",\n")
    
    val cssOutput = cssOutputTpl format(allCssClasses, relPath, cssClassBodies)
    
    IO.write(css, cssOutput)
    
    files.map(_ -> destImage) ++ files.map(_ -> css)
  }
  
  case class SpriteInfo(file: File, width: Int, height: Int, offsetY: Int, cssClass: String)

}