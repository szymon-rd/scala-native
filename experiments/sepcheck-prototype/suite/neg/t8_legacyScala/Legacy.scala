object Legacy {
  var kept: AnyRef = null
  def stash(x: AnyRef): Unit = kept = x
  def len(s: String): Int = s.length
}
