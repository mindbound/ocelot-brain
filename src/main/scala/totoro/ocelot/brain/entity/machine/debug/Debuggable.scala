package totoro.ocelot.brain.entity.machine.debug

abstract class StackFrame

trait Debuggable {
  // Retrieve a list of stack frames in the main thread
  def getStackFrames: List[StackFrame]

  // Request to pause the execution for debugging
  def break(): Unit

  // Continue the execution after a previous stoppage
  def resume(): Unit
}
