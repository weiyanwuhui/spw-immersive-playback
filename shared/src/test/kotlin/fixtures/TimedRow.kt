package fixtures

class TimedRow(val startTime: Long, val endTime: Long, val text: String)
class TimedCaller { fun read(row: TimedRow) = row.startTime }
