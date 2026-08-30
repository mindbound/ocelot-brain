package totoro.ocelot.brain.entity

import totoro.ocelot.brain.entity.TapeDriveState.{FastForwardBytesPerTick, PlayUpdateIntervalNs, State}
import totoro.ocelot.brain.entity.tape.AudioPacketDfpwm
import totoro.ocelot.brain.entity.tape.traits.TapeStorage
import totoro.ocelot.brain.event.{EventBus, TapeDriveStopEvent}

class TapeDriveState(tapeDrive: TapeDrive) {
  var state: State = State.Stopped
  var packetSize: Int = 1500
  private var lastCodecTimeNs: Long = 0
  private var _soundVolume: Int = 127
  var storage: Option[TapeStorage] = None

  def setSpeed(speed: Float): Boolean =
    if (speed < 0.25f || speed > 2f) false
    else {
      packetSize = Math.round(1500 * speed)

      true
    }

  def volume: Byte = _soundVolume.toByte

  def volume_=(volume: Byte): Unit = {
    _soundVolume = volume max 0 min 127
  }

  def setVolume(volume: Float): Unit = {
    val clampedVolume = volume max 0 min 1
    _soundVolume = Math.floor(clampedVolume * 127f).toInt
  }

  def switchState(requestedState: State): Unit = {
    val newState = if (storage.isEmpty) State.Stopped else requestedState

    if (state == State.Playing) {
      EventBus.send(TapeDriveStopEvent(tapeDrive.node.address))
    }

    if (newState == State.Playing) {
      lastCodecTimeNs = System.nanoTime()
    }

    state = newState
  }

  private def advancePosition(): Option[AudioPacketDfpwm] = {
    storage.flatMap { storage =>
      val pktData = Array.ofDim[Byte](packetSize)
      val amount = storage.read(pktData, simulate = false)

      if (amount < packetSize) {
        switchState(State.Stopped)
      }

      Option.when(amount > 0) {
        AudioPacketDfpwm(
          volume,
          frequency = packetSize * 8 * 4,
          if (amount == packetSize) pktData else Array.copyOf(pktData, amount)
        )
      }
    }
  }

  def update(): Option[AudioPacketDfpwm] = (state, storage) match {
    case (State.Stopped, _) | (_, None) => None

    case (State.Playing, Some(storage)) =>
      if (storage.position >= storage.size || storage.position < 0) {
        storage.setPosition(storage.position)
      }

      val time = System.nanoTime()

      if (time - PlayUpdateIntervalNs > lastCodecTimeNs) {
        lastCodecTimeNs += PlayUpdateIntervalNs

        advancePosition()
      } else None

    case (State.Rewinding, Some(storage)) =>
      val sought = storage.seek(-FastForwardBytesPerTick)

      if (sought > -FastForwardBytesPerTick) {
        switchState(State.Stopped)
      }

      None

    case (State.Forwarding, Some(storage)) =>
      val sought = storage.seek(FastForwardBytesPerTick)

      if (sought < FastForwardBytesPerTick) {
        switchState(State.Stopped)
      }

      None

    case _ => None
  }
}

object TapeDriveState {
  private val PlayUpdateIntervalNs = 250 * 1_000_000
  private val FastForwardBytesPerTick = 2048

  type State = State.Value

  object State extends Enumeration {
    val Stopped: Value = Value("STOPPED")
    val Playing: Value = Value("PLAYING")
    val Rewinding: Value = Value("REWINDING")
    val Forwarding: Value = Value("FORWARDING")
  }
}
