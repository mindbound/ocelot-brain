package totoro.ocelot.brain.entity.machine.debug

abstract class StackFrame

trait Debuggable {
  def getStackFrames: List[StackFrame]
}
