package totoro.ocelot.brain.entity

import totoro.ocelot.brain.entity.traits.TextBufferProxy
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.{Message, Node}
import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.util.ColorDepth.ColorDepth
import totoro.ocelot.brain.util.{GenericTextBuffer, PackedColor}
import totoro.ocelot.brain.workspace.Workspace

import java.io.InvalidObjectException

class GpuTextBuffer(val owner: String, val id: Int, val data: GenericTextBuffer) extends TextBufferProxy {

  // the gpu ram does not join nor is searchable to the network
  // this field is required because the api TextBuffer is an Environment
  override def node: Node = {
    throw new InvalidObjectException("GpuTextBuffers do not have nodes")
  }

  override def getMaximumWidth: Int = data.width
  override def getMaximumHeight: Int = data.height
  override def getViewportWidth: Int = data.height
  override def getViewportHeight: Int = data.width

  var dirty: Boolean = true
  override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = dirty = true
  override def onBufferForegroundColorChange(color: PackedColor.Color): Unit = dirty = true
  override def onBufferBackgroundColorChange(color: PackedColor.Color): Unit = dirty = true
  override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = dirty = true
  override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = dirty = true

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
    // the data is initially dirty because other devices don't know about it yet
    data.load(nbt, workspace)
    dirty = true
  }

  override def save(nbt: NBTTagCompound): Unit = {
    data.save(nbt)
    dirty = false
  }

  override def setMaximumResolution(width: Int, height: Int): Unit = {}
  override def setAspectRatio(width: Double, height: Double): Unit = {}
  override def getAspectRatio: Double = 1
  override def setResolution(width: Int, height: Int): Boolean = false
  override def setViewport(width: Int, height: Int): Boolean = false
  override def setMaximumColorDepth(depth: ColorDepth): Unit = {}
  override def getMaximumColorDepth: ColorDepth = data.format.depth

  override def keyDown(character: Char, code: Int, player: User): Unit = {}
  override def keyUp(character: Char, code: Int, player: User): Unit = {}
  override def textInput(codePoint: Int, player: User): Unit = {}
  override def clipboard(value: String, player: User): Unit = {}
  override def mouseDown(x: Double, y: Double, button: Int, player: User): Unit = {}
  override def mouseDrag(x: Double, y: Double, button: Int, player: User): Unit = {}
  override def mouseUp(x: Double, y: Double, button: Int, player: User): Unit = {}
  override def mouseScroll(x: Double, y: Double, delta: Int, player: User): Unit = {}
  override def needUpdate: Boolean = false
  override def update(): Unit = {}
  override def onConnect(node: Node): Unit = {}
  override def onDisconnect(node: Node): Unit = {}
  override def onMessage(message: Message): Unit = {}
}

object GpuTextBuffer {
  def wrap(owner: String, id: Int, data: GenericTextBuffer): GpuTextBuffer = new GpuTextBuffer(owner, id, data)

  def bitblt(dst: TextBufferProxy, col: Int, row: Int, w: Int, h: Int, src: TextBufferProxy, fromCol: Int, fromRow: Int): Unit = {
    val x = col - 1
    val y = row - 1
    val fx = fromCol - 1
    val fy = fromRow - 1
    var adjustedDstX = x
    var adjustedDstY = y
    var adjustedWidth = w
    var adjustedHeight = h
    var adjustedSourceX = fx
    var adjustedSourceY = fy

    if (x < 0) {
      adjustedWidth += x
      adjustedSourceX -= x
      adjustedDstX = 0
    }

    if (y < 0) {
      adjustedHeight += y
      adjustedSourceY -= y
      adjustedDstY = 0
    }

    if (adjustedSourceX < 0) {
      adjustedWidth += adjustedSourceX
      adjustedDstX -= adjustedSourceX
      adjustedSourceX = 0
    }

    if (adjustedSourceY < 0) {
      adjustedHeight += adjustedSourceY
      adjustedDstY -= adjustedSourceY
      adjustedSourceY = 0
    }

    adjustedWidth -= ((adjustedDstX + adjustedWidth) - dst.getWidth) max 0
    adjustedWidth -= ((adjustedSourceX + adjustedWidth) - src.getWidth) max 0

    adjustedHeight -= ((adjustedDstY + adjustedHeight) - dst.getHeight) max 0
    adjustedHeight -= ((adjustedSourceY + adjustedHeight) - src.getHeight) max 0

    // anything left?
    if (adjustedWidth <= 0 || adjustedHeight <= 0) {
      return
    }

    dst match {
      case dstScreen: TextBuffer => src match {
        case srcGpu: GpuTextBuffer => write_vram_to_screen(dstScreen, adjustedDstX, adjustedDstY, adjustedWidth, adjustedHeight, srcGpu, adjustedSourceX, adjustedSourceY)
        case _ => throw new UnsupportedOperationException("Source buffer does not support bitblt operations to a screen")
      }
      case dstGpu: GpuTextBuffer => src match {
        case srcProxy: TextBufferProxy => write_to_vram(dstGpu, adjustedDstX, adjustedDstY, adjustedWidth, adjustedHeight, srcProxy, adjustedSourceX, adjustedSourceY)
        case _ => throw new UnsupportedOperationException("Source buffer does not support bitblt operations")
      }
      case _ => throw new UnsupportedOperationException("Destination buffer does not support bitblt operations")
    }
  }

  def write_vram_to_screen(dstScreen: TextBuffer, x: Int, y: Int, w: Int, h: Int, srcRam: GpuTextBuffer, fx: Int, fy: Int): Boolean = {
    if (dstScreen.data.rawcopy(x + 1, y + 1, w, h, srcRam.data, fx + 1, fy + 1)) {
      // rawcopy returns true only if data was modified
      dstScreen.addBuffer(srcRam)
      dstScreen.onBufferBitBlt(x + 1, y + 1, w, h, srcRam, fx + 1, fy + 1)
      true
    } else false
  }

  def write_to_vram(dstRam: GpuTextBuffer, x: Int, y: Int, w: Int, h: Int, src: TextBufferProxy, fx: Int, fy: Int): Boolean = {
    if (dstRam.data.rawcopy(x + 1, y + 1, w, h, src.data, fx + 1, fy + 1)) {
      dstRam.dirty = true
      true
    } else false
  }
}
