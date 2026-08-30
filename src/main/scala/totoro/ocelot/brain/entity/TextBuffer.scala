package totoro.ocelot.brain.entity

import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context}
import totoro.ocelot.brain.entity.traits.DeviceInfo.{DeviceAttribute, DeviceClass}
import totoro.ocelot.brain.entity.traits.{DeviceInfo, Environment, TextBufferProxy, TieredPersistable, VideoRamRasterizer}
import totoro.ocelot.brain.event._
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.{Component, Network, Visibility}
import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.util.Tier.Tier
import totoro.ocelot.brain.util.{ColorDepth, GenericTextBuffer, PackedColor, Tier}
import totoro.ocelot.brain.workspace.Workspace
import totoro.ocelot.brain.{Constants, Settings}

import scala.collection.mutable

/**
  * This trait implements functionality for displaying and manipulating
  * text, like screens and robots.
  */
class TextBuffer(var bufferTier: Tier = Tier.One)
  extends Environment
    with TextBufferProxy
    with VideoRamRasterizer
    with DeviceInfo
    with TieredPersistable {

  override val node: Component =  Network.newNode(this, Visibility.Network).
    withComponent("screen").
    create()

  private var maxResolution: (Int, Int) = Settings.screenResolutionsByTier(bufferTier.id)

  private var maxDepth: ColorDepth.Value = Settings.screenDepthsByTier(bufferTier.id)

  var aspectRatio: (Double, Double) = (1, 1)

  protected var precisionMode: Boolean = false

  private var isDisplaying: Boolean = true

  var _data = new GenericTextBuffer(maxResolution, PackedColor.Depth.format(maxDepth))

  override def data: GenericTextBuffer = _data

  var viewport: (Int, Int) = _data.size

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Display,
    DeviceAttribute.Description -> "Text buffer",
    DeviceAttribute.Vendor -> Constants.DeviceInfo.DefaultVendor,
    DeviceAttribute.Product -> "Matrix V0",
    DeviceAttribute.Capacity -> (maxResolution._1 * maxResolution._2).toString,
    DeviceAttribute.Width -> Array("1", "4", "8").apply(maxDepth.id)
  )

  override def getDeviceInfo: Map[String, String] = deviceInfo

  override def tier: Tier = bufferTier
  override def tier_=(value: Tier): Unit = {
    bufferTier = value
    maxResolution = Settings.screenResolutionsByTier(bufferTier.id)
    maxDepth = Settings.screenDepthsByTier(bufferTier.id)
    _data = new GenericTextBuffer(maxResolution, PackedColor.Depth.format(maxDepth))
  }

  // ----------------------------------------------------------------------- //

  @Callback(direct = true, doc = """function():boolean -- Returns whether the screen is currently on.""")
  def isOn(computer: Context, args: Arguments): Array[AnyRef] = result(isDisplaying)

  @Callback(doc = """function():boolean -- Turns the screen on. Returns whether the state changed, and whether it is now on.""")
  def turnOn(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = true)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(doc = """function():boolean -- Turns off the screen. Returns whether the state changed, and whether it is now on.""")
  def turnOff(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = false)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(direct = true, doc = """function():number, number -- The aspect ratio of the screen. For multi-block screens this is the number of blocks, horizontal and vertical.""")
  def getAspectRatio(context: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    result(aspectRatio._1, aspectRatio._2)
  }

  @Callback(doc = """function():table -- The list of keyboards attached to the screen.""")
  def getKeyboards(context: Context, args: Arguments): Array[AnyRef] = {
    context.pause(0.25)
    Array(node.neighbors.filter(_.host.isInstanceOf[Keyboard]).map(_.address).toArray)
  }

  @Callback(direct = true, doc = """function():boolean -- Returns whether the screen is in high precision mode (sub-pixel mouse event positions).""")
  def isPrecise(computer: Context, args: Arguments): Array[AnyRef] = result(precisionMode)

  @Callback(doc = """function(enabled:boolean):boolean -- Set whether to use high precision mode (sub-pixel mouse event positions).""")
  def setPrecise(computer: Context, args: Arguments): Array[AnyRef] = {
    // Available for T3 screens only... easiest way to check for us is to
    // base it off of the maximum color depth.
    if (maxDepth == Settings.screenDepthsByTier(Tier.Three.id)) {
      val oldValue = precisionMode
      precisionMode = args.checkBoolean(0)
      result(oldValue)
    }
    else result((), "unsupported operation")
  }

  // ----------------------------------------------------------------------- //

  /**
    * Set whether the buffer is powered on.
    *
    * For example, screens can be powered on and off by sending a redstone
    * pulse into them, in addition to their component API.
    *
    * @param value whether the buffer should be on or not.
    * @see `getPowerState()`
    */
  def setPowerState(value: Boolean): Unit = {
    isDisplaying = value
  }

  /**
    * Get the current power state.
    *
    * @return whether the buffer is powered on.
    * @see `setPowerState(boolean)`
    */
  def getPowerState: Boolean = isDisplaying

  override def setMaximumResolution(width: Int, height: Int): Unit = {
    if (width < 1) throw new IllegalArgumentException("width must be larger or equal to one")
    if (height < 1) throw new IllegalArgumentException("height must be larger or equal to one")
    maxResolution = (width, height)
  }

  override def getMaximumWidth: Int = maxResolution._1

  override def getMaximumHeight: Int = maxResolution._2

  override def setAspectRatio(width: Double, height: Double): Unit = this.synchronized(this.aspectRatio = (width, height))

  override def getAspectRatio: Double = aspectRatio._1 / aspectRatio._2

  override def setResolution(width: Int, height: Int): Boolean = {
    val (mw, mh) = maxResolution
    if (width < 1 || height < 1 || width > mw || height > mw || height * width > mw * mh)
      throw new IllegalArgumentException("unsupported resolution")
    // Send to clients
    EventBus.send(TextBufferSetResolutionEvent(this.node.address, width, height))
    // Force set viewport to new resolution. This is partially for
    // backwards compatibility, and partially to enforce a valid one.
    val sizeChanged = _data.size = (width, height)
    val viewportChanged = setViewport(width, height)
    if (sizeChanged || viewportChanged) {
      if (!viewportChanged && node != null) node.sendToReachable("computer.signal", "screen_resized", Int.box(width), Int.box(height))
      true
    }
    else false
  }

  override def setViewport(width: Int, height: Int): Boolean = {
    val (mw, mh) = _data.size
    if (width < 1 || height < 1 || width > mw || height > mh)
      throw new IllegalArgumentException("unsupported viewport resolution")
    EventBus.send(TextBufferSetViewportEvent(this.node.address, width, height))
    val (cw, ch) = viewport
    if (width != cw || height != ch) {
      viewport = (width, height)
      if (node != null) node.sendToReachable("computer.signal", "screen_resized", Int.box(width), Int.box(height))
      true
    }
    else false
  }

  override def getViewportWidth: Int = viewport._1

  override def getViewportHeight: Int = viewport._2

  override def setMaximumColorDepth(depth: ColorDepth.Value): Unit = maxDepth = depth

  override def getMaximumColorDepth: ColorDepth.Value = maxDepth

  override def setColorDepth(depth: ColorDepth.Value): Boolean = {
    val colorDepthChanged: Boolean = super.setColorDepth(depth)
    EventBus.send(TextBufferSetColorDepthEvent(this.node.address, depth.id))
    colorDepthChanged
  }

  override def onBufferPaletteColorChange(index: Int, color: Int): Unit =
    EventBus.send(TextBufferSetPaletteColorEvent(this.node.address, index, color))

  override def onBufferForegroundColorChange(color: PackedColor.Color): Unit =
    EventBus.send(TextBufferSetForegroundColorEvent(this.node.address, _data.format.inflate(_data.format.deflate(color) & 0xFF)))

  override def onBufferBackgroundColorChange(color: PackedColor.Color): Unit =
    EventBus.send(TextBufferSetBackgroundColorEvent(this.node.address, _data.format.inflate(_data.format.deflate(color) & 0xFF)))

  override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit =
    EventBus.send(TextBufferCopyEvent(this.node.address, col, row, w, h, tx, ty))

  override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit =
    EventBus.send(TextBufferFillEvent(this.node.address, col, row, w, h, c))

  override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit =
    EventBus.send(TextBufferSetEvent(this.node.address, col, row, s, vertical))

  override def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: GpuTextBuffer, fromCol: Int, fromRow: Int): Unit =
    EventBus.send(TextBufferBitBltEvent(this.node.address, col, row, w, h, ram, fromCol, fromRow))

  override def onBufferRamInit(ram: GpuTextBuffer): Unit =
    EventBus.send(TextBufferRamInitEvent(this.node.address, ram))

  override def onBufferRamDestroy(ram: GpuTextBuffer): Unit =
    EventBus.send(TextBufferRamDestroyEvent(this.node.address, ram))

  override def rawSetText(column: Int, row: Int, text: Array[Array[Int]]): Unit =
    super.rawSetText(column, row, text)

  override def rawSetForeground(column: Int, row: Int, color: Array[Array[Int]]): Unit =
    super.rawSetForeground(column, row, color)

  override def rawSetBackground(column: Int, row: Int, color: Array[Array[Int]]): Unit =
    super.rawSetBackground(column, row, color)

  override def keyDown(character: Char, code: Int, player: User): Unit = {
    sendToKeyboards("keyboard.keyDown", player, Char.box(character), Int.box(code))
  }

  override def keyUp(character: Char, code: Int, player: User): Unit = {
    sendToKeyboards("keyboard.keyUp", player, Char.box(character), Int.box(code))
  }

  override def textInput(codePoint: Int, player: User): Unit = {
    sendToKeyboards("keyboard.textInput", player, Int.box(codePoint))
  }

  override def clipboard(value: String, player: User): Unit = {
    sendToKeyboards("keyboard.clipboard", player, value)
  }

  override def mouseDown(x: Double, y: Double, button: Int, player: User): Unit = {
    sendMouseEvent(player, "touch", x, y, button)
  }

  override def mouseDrag(x: Double, y: Double, button: Int, player: User): Unit = {
    sendMouseEvent(player, "drag", x, y, button)
  }

  override def mouseUp(x: Double, y: Double, button: Int, player: User): Unit = {
    sendMouseEvent(player, "drop", x, y, button)
  }

  override def mouseScroll(x: Double, y: Double, delta: Int, player: User): Unit = {
    sendMouseEvent(player, "scroll", x, y, delta)
  }

  private def sendMouseEvent(player: User, name: String, x: Double, y: Double, data: Int): Unit = {
    val args = mutable.ArrayBuffer.empty[AnyRef]

    args += player
    args += name
    if (precisionMode) {
      args += Double.box(x)
      args += Double.box(y)
    }
    else {
      args += Int.box(x.toInt + 1)
      args += Int.box(y.toInt + 1)
    }
    args += Int.box(data)
    if (Settings.get.inputUsername) {
      args += player.nickname
    }

    node.sendToReachable("computer.checked_signal", args.toSeq: _*)
  }

  private def sendToKeyboards(name: String, values: AnyRef*): Unit = {
    node.sendToNeighbors(name, values: _*)
  }

  // ----------------------------------------------------------------------- //

  private final val DataTag = "data"
  private final val IsOnTag = "isOn"
  private final val PreciseTag = "precise"

  private final val MaxWidthTag = "maxWidth"
  private final val MaxHeightTag = "maxHeight"

  private final val ViewportWidthTag = "viewportWidth"
  private final val ViewportHeightTag = "viewportHeight"

  private final val AspectRatioWidthTag = "aspectRatioWidth"
  private final val AspectRatioHeightTag = "aspectRatioHeight"

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
    super.load(nbt, workspace)

    if (nbt.hasKey(DataTag)) {
      _data.load(nbt.getCompoundTag(DataTag), workspace)
    }

    if (nbt.hasKey(IsOnTag)) {
      isDisplaying = nbt.getBoolean(IsOnTag)
    }
    if (nbt.hasKey(MaxWidthTag) && nbt.hasKey(MaxHeightTag)) {
      val maxWidth = nbt.getInteger(MaxWidthTag)
      val maxHeight = nbt.getInteger(MaxHeightTag)
      maxResolution = (maxWidth, maxHeight)
    }
    precisionMode = nbt.getBoolean(PreciseTag)

    if (nbt.hasKey(ViewportWidthTag)) {
      val vpw = nbt.getInteger(ViewportWidthTag)
      val vph = nbt.getInteger(ViewportHeightTag)
      viewport = (vpw min _data.width max 1, vph min _data.height max 1)
    } else {
      viewport = _data.size
    }

    aspectRatio =
      if (nbt.hasKey(AspectRatioWidthTag) && nbt.hasKey(AspectRatioHeightTag))
        (nbt.getDouble(AspectRatioWidthTag), nbt.getDouble(AspectRatioHeightTag))
      else
        (1, 1)
  }

  override def save(nbt: NBTTagCompound): Unit = {
    super.save(nbt)
    // Happy thread synchronization hack! Here's the problem: GPUs allow direct
    // calls for modifying screens to give a more responsive experience. This
    // causes the following problem: when saving, if the screen is saved first,
    // then the executor runs in parallel and changes the screen *before* the
    // server thread begins saving that computer, the saved computer will think
    // it changed the screen, although the saved screen wasn't. To avoid that we
    // wait for all computers the screen is connected to to finish their current
    // execution and pausing them (which will make them resume in the next tick
    // when their update() runs).
    if (node.network != null) {
      for (node <- node.network.nodes) node.host match {
        case computer: traits.Computer if !computer.machine.isPaused =>
          computer.machine.pause(0.1)
        case _ =>
      }
    }

    val dataNbt = new NBTTagCompound()
    _data.save(dataNbt)
    nbt.setTag(DataTag, dataNbt)

    nbt.setBoolean(IsOnTag, isDisplaying)
    nbt.setBoolean(PreciseTag, precisionMode)

    nbt.setInteger(MaxWidthTag, maxResolution._1)
    nbt.setInteger(MaxHeightTag, maxResolution._2)

    nbt.setInteger(ViewportWidthTag, viewport._1)
    nbt.setInteger(ViewportHeightTag, viewport._2)

    nbt.setDouble(AspectRatioWidthTag, aspectRatio._1)
    nbt.setDouble(AspectRatioHeightTag, aspectRatio._2)
  }
}
