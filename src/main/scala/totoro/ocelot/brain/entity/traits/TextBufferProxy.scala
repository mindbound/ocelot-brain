package totoro.ocelot.brain.entity.traits

import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.util.{ColorDepth, ExtendedUnicodeHelper, GenericTextBuffer, PackedColor}

trait TextBufferProxy extends Environment {
  def data: GenericTextBuffer

  /**
    * Get the current horizontal resolution.
    *
    * @see `setResolution(int, int)`
    */
  def getWidth: Int = data.width

  /**
    * Get the current vertical resolution.
    *
    * @see `setResolution(int, int)`
    */
  def getHeight: Int = data.height

  /**
    * Get the maximum horizontal size of the buffer.
    */
  def getMaximumWidth: Int

  /**
    * Get the maximum vertical size of the buffer.
    */
  def getMaximumHeight: Int

  /**
    * Get the current horizontal viewport resolution.
    */
  def getViewportWidth: Int

  /**
    * Get the current vertical viewport resolution.
    */
  def getViewportHeight: Int

  /**
    * Sets the maximum resolution supported by this buffer.
    *
    * @param width  the maximum horizontal resolution, in characters.
    * @param height the maximum vertical resolution, in characters.
    */
  def setMaximumResolution(width: Int, height: Int): Unit

  /**
    * Set the 'aspect ratio' of the buffer.
    *
    * Not to be confused with the maximum resolution of the buffer, this
    * refers to the 'physical' size of the buffer's container. For multi-
    * block screens, for example, this is the number of horizontal and
    * vertical blocks.
    *
    * @param width  the horizontal size of the physical representation.
    * @param height the vertical size of the physical representation.
    */
  def setAspectRatio(width: Double, height: Double): Unit

  /**
    * Get the aspect ratio of the buffer.
    *
    * Note that this is in fact `width / height`.
    *
    * @see #setAspectRatio(double, double)
    */
  def getAspectRatio: Double

  /**
    * Set the buffer's active resolution.
    *
    * @param width  the horizontal resolution.
    * @param height the vertical resolution.
    * @return `true` if the resolution changed.
    */
  def setResolution(width: Int, height: Int): Boolean

  /**
    * Set the buffer's active viewport resolution.
    *
    * This cannot exceed the current buffer resolution.
    *
    * @param width  the horizontal resolution.
    * @param height the vertical resolution.
    * @return `true` if the resolution changed.
    * @see `setResolution(int, int)`
    */
  def setViewport(width: Int, height: Int): Boolean

  /**
    * Sets the maximum color depth supported by this buffer.
    *
    * Note that this is the ''maximum'' supported depth, lower depths
    * will be supported, too. So when setting this to four bit, one bit will
    * be supported, too. When setting this to eight bit, four and one bit
    * will be supported, also.
    *
    * @param depth the maximum color depth of the buffer.
    */
  def setMaximumColorDepth(depth: ColorDepth.Value): Unit

  /**
    * Get the maximum color depth supported.
    */
  def getMaximumColorDepth: ColorDepth.Value

  /**
    * Set the active color depth for this buffer.
    *
    * @param depth the new color depth.
    * @return `true` if the color depth changed.
    */
  def setColorDepth(depth: ColorDepth.Value): Boolean = {
    if (depth.id > getMaximumColorDepth.id)
      throw new IllegalArgumentException("unsupported depth")
    data.format = PackedColor.Depth.format(depth)
  }

  /**
    * Get the active color depth of this buffer.
    */
  def getColorDepth: ColorDepth.Value = data.format.depth

  def onBufferPaletteColorChange(index: Int, color: Int): Unit = {}

  /**
    * Set the color of the active color palette at the specified index.
    *
    * This will error if the current depth does not have a palette (one bit).
    *
    * @param index the index at which to set the color.
    * @param color the color to set for the specified index.
    */
  def setPaletteColor(index: Int, color: Int): Unit = data.format match {
    case palette: PackedColor.MutablePaletteFormat =>
      palette(index) = color
      onBufferPaletteColorChange(index, color)
    case _ => throw new Exception("palette not available")
  }

  /**
    * Get the color in the active color palette at the specified index.
    *
    * This will error if the current depth does not have a palette (one bit).
    *
    * @param index the index at which to get the color.
    * @return the color in the active palette at the specified index.
    */
  def getPaletteColor(index: Int): Int = data.format match {
    case palette: PackedColor.MutablePaletteFormat => palette(index)
    case _ => throw new Exception("palette not available")
  }

  def onBufferForegroundColorChange(color: PackedColor.Color): Unit = {}

  /**
    * Set the active foreground color, not using a palette.
    *
    * @param color the new foreground color.
    * @see `setForegroundColor(int, boolean)`
    */
  def setForegroundColor(color: Int): Unit = setForegroundColor(color, isFromPalette = false)

  /**
    * Set the active foreground color.
    *
    * If the value is not from the palette, the actually stored value may
    * differ from the specified one, as it is converted to the buffer's
    * current color depth.
    *
    * For palette-only color formats (four bit) the best fit from the palette
    * is chosen, if the value is not from the palette.
    *
    * @param color         the color or palette index.
    * @param isFromPalette `true` if `color` specifies a palette index.
    */
  def setForegroundColor(color: Int, isFromPalette: Boolean): Unit = {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.foreground != value) {
      data.foreground = value
      onBufferForegroundColorChange(value)
    }
  }

  /**
    * The active foreground color.
    */
  def getForegroundColor: Int = data.foreground.value

  /**
    * `true` if the foreground color is from the color palette, meaning
    * the value returned from `getForegroundColor()` is the color
    * palette index.
    */
  def isForegroundFromPalette: Boolean = data.foreground.isPalette

  def onBufferBackgroundColorChange(color: PackedColor.Color): Unit = {}

  /**
    * Set the active background color, not using a palette.
    *
    * @param color the new background color.
    * @see `setBackgroundColor(int, boolean)`
    */
  def setBackgroundColor(color: Int): Unit = setBackgroundColor(color, isFromPalette = false)

  /**
    * Set the active background color.
    *
    * If the value is not from the palette, the actually stored value may
    * differ from the specified one, as it is converted to the buffer's
    * current color depth.
    *
    * For palette-only color formats (four bit) the best fit from the palette
    * is chosen, if the value is not from the palette.
    *
    * @param color         the color or palette index.
    * @param isFromPalette `true` if `color` specifies a palette index.
    */
  def setBackgroundColor(color: Int, isFromPalette: Boolean): Unit = {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.background != value) {
      data.background = value
      onBufferBackgroundColorChange(value)
    }
  }

  /**
    * The active background color.
    */
  def getBackgroundColor: Int = data.background.value

  /**
    * `true` if the background color is from the color palette, meaning
    * the value returned from `getBackgroundColor()` is the color
    * palette index.
    */
  def isBackgroundFromPalette: Boolean = data.background.isPalette

  def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {}

  /**
    * Copy a portion of the text buffer.
    *
    * This will copy the area's text and colors.
    *
    * @param column                the starting horizontal index of the area to copy.
    * @param row                   the starting vertical index of the area to copy.
    * @param width                 the width of the area to copy.
    * @param height                the height of the area to copy.
    * @param horizontalTranslation the horizontal offset, relative to the starting column to copy the are to.
    * @param verticalTranslation   the vertical offset, relative to the starting row to copy the are to.
    */
  def copy(column: Int, row: Int, width: Int, height: Int, horizontalTranslation: Int, verticalTranslation: Int): Unit =
    if (data.copy(column, row, width, height, horizontalTranslation, verticalTranslation))
      onBufferCopy(column, row, width, height, horizontalTranslation, verticalTranslation)

  def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {}

  /**
    * Fill a portion of the text buffer.
    *
    * This will set the area's colors to the currently active ones.
    *
    * @param column the starting horizontal index of the area to fill.
    * @param row    the starting vertical index of the area to fill.
    * @param width  the width of the area to fill.
    * @param height the height of the area to fill.
    * @param value  the character to fill the area with.
    * @deprecated Please use the int variant.
    */
  @Deprecated
  def fill(column: Int, row: Int, width: Int, height: Int, value: Char): Unit =
    fill(column, row, width, height, value.toInt)

  /**
    * Fill a portion of the text buffer.
    *
    * This will set the area's colors to the currently active ones.
    *
    * @param column the starting horizontal index of the area to fill.
    * @param row    the starting vertical index of the area to fill.
    * @param width  the width of the area to fill.
    * @param height the height of the area to fill.
    * @param value  the code point to fill the area with.
    */
  def fill(column: Int, row: Int, width: Int, height: Int, value: Int): Unit =
    if (data.fill(column, row, width, height, value))
      onBufferFill(column, row, width, height, value)

  def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = {}

  private def truncate(s: String, sLength: Int, leftOffset: Int, maxWidth: Int): String = {
    val subFrom = s.offsetByCodePoints(0, leftOffset)
    val width = math.min(sLength, maxWidth)
    if (width <= 0) ""
    else if ((sLength - leftOffset) <= width) s
    else s.substring(subFrom, s.offsetByCodePoints(subFrom, width))
  }

  /**
    * Write a string into the text buffer.
    *
    * This will apply the currently active colors to the changed area.
    *
    * @param column   the starting horizontal index to write at.
    * @param row      the starting vertical index to write at.
    * @param value    the string to write.
    * @param vertical `true` if the string should be written vertically instead of horizontally.
    */
  def set(column: Int, row: Int, value: String, vertical: Boolean): Unit = {
    val sLength = ExtendedUnicodeHelper.length(value)
    if (column < data.width && (column >= 0 || -column < sLength)) {
      // Make sure the string isn't longer than it needs to be, in particular to
      // avoid sending too much data to our clients.
      val (x, y, truncated) =
      if (vertical) {
        if (row < 0) (column, 0, truncate(value, sLength, -row, data.height))
        else (column, row, truncate(value, sLength, 0, data.height - row))
      }
      else {
        if (column < 0) (0, row, truncate(value, sLength, -column, data.width))
        else (column, row, truncate(value, sLength, 0, data.width - column))
      }
      if (data.set(x, y, truncated, vertical))
        onBufferSet(x, row, truncated, vertical)
    }
  }

  /**
    * Get the character in the text buffer at the specified location.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return the character at that index.
    * @deprecated Please use getCodePoint going forward.
    */
  @Deprecated
  def get(column: Int, row: Int): Char = data.get(column, row).toChar

  /**
    * Get the code point in the text buffer at the specified location.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return the character at that index.
    */
  def getCodePoint(column: Int, row: Int): Int = data.get(column, row)

  /**
    * Get the foreground color of the text buffer at the specified location.
    *
    * '''Important''': this may be a palette index.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return the foreground color at that index.
    */
  def getForegroundColor(column: Int, row: Int): Int =
    if (isForegroundFromPalette(column, row)) {
      PackedColor.extractForeground(color(column, row))
    }
    else {
      PackedColor.unpackForeground(color(column, row), data.format)
    }

  /**
    * Whether the foreground color of the text buffer at the specified
    * location if from the color palette.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return whether the foreground at that index is from the palette.
    */
  def isForegroundFromPalette(column: Int, row: Int): Boolean =
    data.format.isFromPalette(PackedColor.extractForeground(color(column, row)))

  /**
    * Get the background color of the text buffer at the specified location.
    *
    * '''Important''': this may be a palette index.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return the background color at that index.
    */
  def getBackgroundColor(column: Int, row: Int): Int =
    if (isBackgroundFromPalette(column, row)) {
      PackedColor.extractBackground(color(column, row))
    }
    else {
      PackedColor.unpackBackground(color(column, row), data.format)
    }

  /**
    * Whether the background color of the text buffer at the specified
    * location if from the color palette.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @return whether the background at that index is from the palette.
    */
  def isBackgroundFromPalette(column: Int, row: Int): Boolean =
    data.format.isFromPalette(PackedColor.extractBackground(color(column, row)))

  /**
    * Overwrites a portion of the text in raw mode.
    *
    * This will copy the given char array into the buffer, starting at the
    * specified column and row. The array is expected to be indexed row-
    * first, i.e. the first dimension is the vertical axis, the second
    * the horizontal.
    *
    * '''Important''': this performs no checks as to whether something
    * actually changed. It will always send the changed patch to clients.
    * It will also not crop the specified array to the actually used range.
    * In other words, this is not intended to be exposed as-is to user code,
    * it should always be called with validated, and, as necessary, cropped
    * values.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @param text   the text to write.
    * @deprecated Please use the int[][] variant.
    */
  @Deprecated
  def rawSetText(column: Int, row: Int, text: Array[Array[Char]]): Unit = {
    for (y <- row until ((row + text.length) min data.height)) {
      val line = text(y - row)
      Array.copy(line, 0, data.buffer(y), column, line.length min data.width)
    }
  }

  /**
    * Overwrites a portion of the text in raw mode.
    *
    * This will copy the given char array into the buffer, starting at the
    * specified column and row. The array is expected to be indexed row-
    * first, i.e. the first dimension is the vertical axis, the second
    * the horizontal.
    *
    * '''Important''': this performs no checks as to whether something
    * actually changed. It will always send the changed patch to clients.
    * It will also not crop the specified array to the actually used range.
    * In other words, this is not intended to be exposed as-is to user code,
    * it should always be called with validated, and, as necessary, cropped
    * values.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @param text   the text code points to write.
    */
  def rawSetText(column: Int, row: Int, text: Array[Array[Int]]): Unit = {
    for (y <- row until ((row + text.length) min data.height)) {
      val line = text(y - row)
      Array.copy(line, 0, data.buffer(y), column, line.length min data.width)
    }
  }

  /**
    * Overwrites a portion of the foreground color information in raw mode.
    *
    * This will convert the specified RGB data (in `0xRRGGBB` format)
    * to the internal, packed representation and copy it into the buffer,
    * starting at the specified column and row. The array is expected to be
    * indexed row-first, i.e. the first dimension is the vertical axis, the
    * second the horizontal.
    *
    * '''Important''': this performs no checks as to whether something
    * actually changed. It will always send the changed patch to clients.
    * It will also not crop the specified array to the actually used range.
    * In other words, this is not intended to be exposed as-is to user code,
    * it should always be called with validated, and, as necessary, cropped
    * values.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @param color  the foreground color data to write.
    */
  def rawSetForeground(column: Int, row: Int, color: Array[Array[Int]]): Unit = {
    for (y <- row until ((row + color.length) min data.height)) {
      val line = color(y - row)
      for (x <- column until ((column + line.length) min data.width)) {
        val packedBackground = data.color(y)(x) & 0x00FF
        val packedForeground = (data.format.deflate(PackedColor.Color(line(x - column))) << PackedColor.ForegroundShift) & 0xFF00
        data.color(y)(x) = (packedForeground | packedBackground).toShort
      }
    }
  }

  /**
    * Overwrites a portion of the background color information in raw mode.
    *
    * This will convert the specified RGB data (in `0xRRGGBB` format)
    * to the internal, packed representation and copy it into the buffer,
    * starting at the specified column and row. The array is expected to be
    * indexed row-first, i.e. the first dimension is the vertical axis, the
    * second the horizontal.
    *
    * '''Important''': this performs no checks as to whether something
    * actually changed. It will always send the changed patch to clients.
    * It will also not crop the specified array to the actually used range.
    * In other words, this is not intended to be exposed as-is to user code,
    * it should always be called with validated, and, as necessary, cropped
    * values.
    *
    * @param column the horizontal index.
    * @param row    the vertical index.
    * @param color  the background color data to write.
    */
  def rawSetBackground(column: Int, row: Int, color: Array[Array[Int]]): Unit = {
    for (y <- row until ((row + color.length) min data.height)) {
      val line = color(y - row)
      for (x <- column until ((column + line.length) min data.width)) {
        val packedBackground = data.format.deflate(PackedColor.Color(line(x - column))) & 0x00FF
        val packedForeground = data.color(y)(x) & 0xFF00
        data.color(y)(x) = (packedForeground | packedBackground).toShort
      }
    }
  }

  private def color(column: Int, row: Int): Short = {
    if (column < 0 || column >= getWidth || row < 0 || row >= getHeight)
      throw new IndexOutOfBoundsException()
    else data.color(row)(column)
  }

  /**
    * Signals a key down event for the buffer.
    *
    * This will trigger a message that will be picked up by
    * keyboards, which will then cause a signal in attached machines.
    *
    * @param character the character of the pressed key.
    * @param code      the key code of the pressed key.
    * @param player    the player that pressed the key. Pass `null` on the client side.
    */
  def keyDown(character: Char, code: Int, player: User): Unit

  /**
    * Signals a key up event for the buffer.
    *
    * This will trigger a message that will be picked up by
    * keyboards, which will then cause a signal in attached machines.
    *
    * @param character the character of the released key.
    * @param code      the key code of the released key.
    * @param player    the player that released the key. Pass `null` on the client side.
    */
  def keyUp(character: Char, code: Int, player: User): Unit

  /**
    * Signals a code-point (text) event for the buffer.
    *
    * This will trigger a message that will be picked up by
    * keyboards, which will then cause a signal in attached machines.
    *
    * @param codePoint the code point being typed.
    * @param player    the player that typed the code point. Pass `null` on the client side.
    */
  def textInput(codePoint: Int, player: User): Unit

  /**
    * Signals a clipboard paste event for the buffer.
    *
    * This will trigger a message that will be picked up by
    * keyboards, which will then cause a signal in attached machines.
    *
    * @param value  the text that was pasted.
    * @param player the player that pasted the text. Pass `null` on the client side.
    */
  def clipboard(value: String, player: User): Unit

  /**
    * Signals a mouse button down event for the buffer.
    *
    * This will cause a signal in attached machines.
    *
    * @param x      the horizontal coordinate of the mouse, in characters.
    * @param y      the vertical coordinate of the mouse, in characters.
    * @param button the button of the mouse that was pressed.
    * @param player the player that pressed the mouse button. Pass `null` on the client side.
    */
  def mouseDown(x: Double, y: Double, button: Int, player: User): Unit

  /**
    * Signals a mouse drag event for the buffer.
    *
    * This will cause a signal in attached machines.
    *
    * @param x      the horizontal coordinate of the mouse, in characters.
    * @param y      the vertical coordinate of the mouse, in characters.
    * @param button the button of the mouse that is pressed.
    * @param player the player that moved the mouse. Pass `null` on the client side.
    */
  def mouseDrag(x: Double, y: Double, button: Int, player: User): Unit

  /**
    * Signals a mouse button release event for the buffer.
    *
    * This will cause a signal in attached machines.
    *
    * @param x      the horizontal coordinate of the mouse, in characters.
    * @param y      the vertical coordinate of the mouse, in characters.
    * @param button the button of the mouse that was released.
    * @param player the player that released the mouse button. Pass `null` on the client side.
    */
  def mouseUp(x: Double, y: Double, button: Int, player: User): Unit

  /**
    * Signals a mouse wheel scroll event for the buffer.
    *
    * This will cause a signal in attached machines.
    *
    * @param x      the horizontal coordinate of the mouse, in characters.
    * @param y      the vertical coordinate of the mouse, in characters.
    * @param delta  indicates the direction of the mouse scroll.
    * @param player the player that scrolled the mouse wheel. Pass `null` on the client side.
    */
  def mouseScroll(x: Double, y: Double, delta: Int, player: User): Unit
}
