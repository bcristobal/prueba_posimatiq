import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class Departure(
    val line: String,
    val destination: String,
    val timeSeconds: Int,
    val minutesLeft: Int
)

data class TripInfo(
    val routeId: String,
    val headsign: String
)

// Converts "HH:MM:SS" into total seconds since midnight (supports hours >= 24 like "25:10:00")
fun timeToSeconds(timeText: String): Int? {
    val parts = timeText.trim().split(":")
    if (parts.size != 3) return null

    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    val seconds = parts[2].toIntOrNull() ?: return null

    return (hours * 3600) + (minutes * 60) + seconds
}

// Converts seconds back to standard 24h format "HH:MM:SS"
fun secondsToTime(totalSeconds: Int): String {
    val secondsInDay = 86400
    val normalized = ((totalSeconds % secondsInDay) + secondsInDay) % secondsInDay

    val hours = normalized / 3600
    val minutes = (normalized % 3600) / 60
    val seconds = normalized % 60

    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

// Splits a CSV line ignoring commas that appear inside double quotes
fun splitCsvLine(line: String): List<String> {
    val columns = mutableListOf<String>()
    val currentValue = StringBuilder()
    var insideQuotes = false

    for (character in line) {
        if (character == '\u0022') {
            insideQuotes = !insideQuotes
        } else if (character == ',' && !insideQuotes) {
            columns.add(currentValue.toString())
            currentValue.clear()
        } else {
            currentValue.append(character)
        }
    }
    columns.add(currentValue.toString())
    return columns
}

// Reads a CSV stream line by line (low memory usage) and maps each row by column name
fun readCsvRows(input: InputStream?, onRow: (Map<String, String>) -> Unit) {
    if (input == null) return

    input.bufferedReader().useLines { lines ->
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return

        val headerLine = iterator.next().removePrefix("\uFEFF")
        val headers = splitCsvLine(headerLine).map { it.trim() }

        while (iterator.hasNext()) {
            val line = iterator.next()
            if (line.isBlank()) continue

            val values = splitCsvLine(line)
            val row = mutableMapOf<String, String>()

            for (i in headers.indices) {
                val columnName = headers[i]
                val cellValue = if (i < values.size) values[i].trim() else ""
                row[columnName] = cellValue
            }

            onRow(row)
        }
    }
}

fun getNextDepartures(
    stopId: String,
    date: LocalDate = LocalDate.now(),
    nowSeconds: Int = LocalTime.now().toSecondOfDay(),
    limit: Int = 3,
    openStream: (String) -> InputStream?
): Pair<String, List<Departure>> {

    // 1. Find the readable name of the target stop
    var stopName = "Unknown Stop"
    readCsvRows(openStream("stops.txt")) { row ->
        if (row["stop_id"] == stopId) {
            stopName = row["stop_name"] ?: "Unknown Stop"
        }
    }

    // 2. Find which service IDs are active on the given date
    val activeServices = mutableSetOf<String>()
    val dateText = date.format(DateTimeFormatter.BASIC_ISO_DATE) // Format: yyyyMMdd
    val dayOfWeekColumn = date.dayOfWeek.name.lowercase()        // e.g., "monday"

    readCsvRows(openStream("calendar.txt")) { row ->
        val startDate = row["start_date"] ?: ""
        val endDate = row["end_date"] ?: ""
        val runsToday = row[dayOfWeekColumn] == "1"
        val serviceId = row["service_id"] ?: ""

        if (dateText in startDate..endDate && runsToday && serviceId.isNotEmpty()) {
            activeServices.add(serviceId)
        }
    }

    // Apply holiday / special exceptions from calendar_dates.txt
    readCsvRows(openStream("calendar_dates.txt")) { row ->
        if (row["date"] == dateText) {
            val serviceId = row["service_id"] ?: ""
            val exceptionType = row["exception_type"]
            if (exceptionType == "1") activeServices.add(serviceId)
            if (exceptionType == "2") activeServices.remove(serviceId)
        }
    }

    // 3. Load route names (e.g., route_id -> "A3527")
    val routes = mutableMapOf<String, String>()
    readCsvRows(openStream("routes.txt")) { row ->
        val routeId = row["route_id"] ?: ""
        val shortName = row["route_short_name"] ?: ""
        val longName = row["route_long_name"] ?: ""
        if (routeId.isNotEmpty()) {
            routes[routeId] = shortName.ifBlank { longName }
        }
    }

    // 4. Load trips that operate today
    val activeTrips = mutableMapOf<String, TripInfo>()
    readCsvRows(openStream("trips.txt")) { row ->
        val serviceId = row["service_id"] ?: ""
        if (serviceId in activeServices) {
            val tripId = row["trip_id"] ?: ""
            val routeId = row["route_id"] ?: ""
            val headsign = row["trip_headsign"] ?: ""
            if (tripId.isNotEmpty()) {
                activeTrips[tripId] = TripInfo(routeId, headsign)
            }
        }
    }

    // 5. Filter upcoming stop times for our stop
    val upcomingDepartures = mutableListOf<Departure>()
    readCsvRows(openStream("stop_times.txt")) { row ->
        if (row["stop_id"] != stopId) return@readCsvRows

        val tripId = row["trip_id"] ?: ""
        val trip = activeTrips[tripId] ?: return@readCsvRows

        val departureText = row["departure_time"] ?: ""
        val departureSeconds = timeToSeconds(departureText) ?: return@readCsvRows

        if (departureSeconds >= nowSeconds) {
            val lineName = routes[trip.routeId] ?: trip.routeId
            val destination = trip.headsign.ifBlank { "N/A" }
            val minutesLeft = (departureSeconds - nowSeconds) / 60

            upcomingDepartures.add(
                Departure(lineName, destination, departureSeconds, minutesLeft)
            )
        }
    }

    val nextThree = upcomingDepartures
        .sortedBy { it.timeSeconds }
        .take(limit)

    return Pair(stopName, nextThree)
}

fun formatAsMarkdown(
    stopName: String,
    stopId: String,
    date: LocalDate,
    nowSeconds: Int,
    departures: List<Departure>
): String {
    val builder = StringBuilder()
    builder.appendLine("# Next Departures — $stopName (`$stopId`)")
    builder.appendLine("- **Date:** `$date` | **Time:** `${secondsToTime(nowSeconds)}`")
    builder.appendLine()

    if (departures.isEmpty()) {
        builder.appendLine("_No upcoming departures for today._")
    } else {
        builder.appendLine("| Line | Destination | Departure | In |")
        builder.appendLine("| :--- | :--- | :--- | :--- |")
        for (departure in departures) {
            val formattedTime = secondsToTime(departure.timeSeconds)
            builder.appendLine(
                "| **${departure.line}** | ${departure.destination} | `$formattedTime` | ${departure.minutesLeft} min |"
            )
        }
    }

    return builder.toString()
}

fun main(args: Array<String>) {
    val gtfsFolder = if (args.isNotEmpty()) File(args[0]) else File("./bizkaibus")
    val targetStopId = if (args.size > 1) args[1] else "4255"
    val targetDate = if (args.size > 2) LocalDate.parse(args[2]) else LocalDate.now()
    val currentSeconds = if (args.size > 3) {
        timeToSeconds(args[3]) ?: LocalTime.now().toSecondOfDay()
    } else {
        LocalTime.now().toSecondOfDay()
    }

    val (stopName, departures) = getNextDepartures(
        stopId = targetStopId,
        date = targetDate,
        nowSeconds = currentSeconds
    ) { fileName ->
        val file = File(gtfsFolder, fileName)
        if (file.exists()) file.inputStream() else null
    }

    val markdownOutput = formatAsMarkdown(
        stopName = stopName,
        stopId = targetStopId,
        date = targetDate,
        nowSeconds = currentSeconds,
        departures = departures
    )
    println(markdownOutput)
}