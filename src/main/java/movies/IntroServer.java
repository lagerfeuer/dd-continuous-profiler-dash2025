package movies;

import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;

import java.io.InputStreamReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Request;
import spark.Response;

public class IntroServer {
	private static HashMap<Request, Integer> REQUEST_METRICS = new HashMap<Request, Integer>();
	private static final Gson GSON = new GsonBuilder().setLenient().setPrettyPrinting().create();
	private static final Logger LOG = LoggerFactory.getLogger(IntroServer.class);
	private static final String MONGO_URI = System.getenv("MONGO_URI");

	private static final Supplier<List<Movie>> MOVIES = cache(IntroServer::loadMovies);
	private static final Supplier<List<Credit>> CREDITS = cache(IntroServer::loadCredits);
	private static final int MOVIES_API_PORT = Integer.parseInt(System.getenv("MOVIES_API_PORT"));
	private static final Supplier<Map<String, List<Credit>>> CREDITS_BY_MOVIE_ID = cache(() -> CREDITS.get().stream().collect(Collectors.groupingBy(c -> c.id)));

	public static void main(String[] args) {
		port(MOVIES_API_PORT);
		ipAddress("0.0.0.0");
		get("/", IntroServer::randomMovieEndpoint);
		get("/credits", IntroServer::creditsEndpoint);
		get("/movies", IntroServer::moviesEndpoint);
		get("/old-movies", IntroServer::oldMoviesEndpoint);
		get("/stats", IntroServer::statsEndpoint);
		exception(Exception.class, (exception, request, response) -> exception.printStackTrace());

		// Warm these up at application start
		MOVIES.get();
		CREDITS.get();

		var version = System.getProperty("dd.version");
		LOG.info("Running version " + (version != null ? version.toLowerCase() : "(not set)") + " with pid " + ProcessHandle.current().pid());
	}

	private static void collectMetrics(Request req) {
		var count = REQUEST_METRICS.get(req);
		if (count == null) {
			count = 1;
		} else {
			count++;
		}
		REQUEST_METRICS.put(req, count);
	}

	private static Object randomMovieEndpoint(Request req, Response res) {
		collectMetrics(req);
		return replyJSON(res, MOVIES.get().get(new Random().nextInt(MOVIES.get().size())));
	}

	private static Object creditsEndpoint(Request req, Response res) {
		var movies = MOVIES.get().stream();
		var query = req.queryParamOrDefault("q", req.queryParams("query"));

		if (query != null) {
			var p = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
			movies = movies.filter(m -> m.title != null && p.matcher(m.title).find());
		}

		var moviesWithCredits = movies.map(movie -> new MovieWithCredits(movie, creditsForMovie(movie)));
		return replyJSON(res, moviesWithCredits);
	}

	private static synchronized Object statsEndpoint(Request req, Response res) {
		var movies = MOVIES.get().stream();
		var query = req.queryParamOrDefault("q", req.queryParams("query"));

		if (query != null) {
			var p = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
			movies = movies.filter(m -> m.title != null && p.matcher(m.title).find());
		}

		var selectedMovies = movies.toList();

		var numberMatched = selectedMovies.size();
		var statsForMovies = selectedMovies.stream().map(movie -> crewCountForMovie(creditsForMovie(movie)));
		var aggregatedStats =
			statsForMovies
				.flatMap(countMap -> countMap.entrySet().stream())
				.collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue)));

		return replyJSON(res, new StatsResult(numberMatched, aggregatedStats));
	}

	private static List<Credit> creditsForMovie(Movie movie) {
		return CREDITS_BY_MOVIE_ID.get().get(movie.id);
	}

	private static Map<CrewRole, Long> crewCountForMovie(List<Credit> credits) {
		var credit = credits != null ? credits.get(0) : null;
		return credit != null ?
			credit.crewRole.stream().collect(Collectors.groupingBy(CrewRole::parseRole, Collectors.counting())) : Map.of();
	}

	private static Object moviesEndpoint(Request req, Response res) {
		var movies = MOVIES.get();
		movies = sortByDescReleaseDate(movies);
		var query = req.queryParamOrDefault("q", req.queryParams("query"));
		if (query != null) {
			movies = movies.stream().filter(m -> m.title.toUpperCase().matches(".*" + query.toUpperCase() + ".*")).toList();
		}
		return replyJSON(res, movies);
	}

	private static List<Movie> sortByDescReleaseDate(List<Movie> movies) {
		var sortedMovies = new ArrayList<Movie>(movies);
		sortedMovies.sort(Comparator.comparing((Movie m) -> {
			try {
				return LocalDate.parse(m.releaseDate);
			} catch (Exception e) {
				return LocalDate.MIN;
			}
		}).reversed());
		return sortedMovies;
	}

	private static Object oldMoviesEndpoint(Request req, Response res) {
		var year = req.queryParamOrDefault("year", "2010");
		var limit = Integer.valueOf(req.queryParamOrDefault("n", "10"));

		var oldMovies = MOVIES.get().stream().filter(m -> isOlderThan(year, m)).toList();
		LOG.atDebug().log(() -> "Found the following oldMovies: " + oldMovies);
		var limitedMovies = oldMovies.stream().limit(limit).toList();
		LOG.atDebug().log(() -> "With limit " + limit + ", the result was: " + limitedMovies);

		return replyJSON(res, limitedMovies);
	}

	private static boolean isOlderThan(String year, Movie movie) {
		var result = movie.releaseDate.compareTo(year) < 0;
		LOG.atDebug().log(() -> "Is " + movie + " older than " + year + "? " + result);
		return result;
	}

	private static Object replyJSON(Response res, Stream<?> data) { return replyJSON(res, data.toList()); }
	private static Object replyJSON(Response res, Object data) {
		res.type("application/json");
		return GSON.toJson(data);
	}

	private static List<Movie> loadMovies() {
		try (
			var is = ClassLoader.getSystemResourceAsStream("movies-v2.json.gz");
			var gzis = new GZIPInputStream(is);
			var reader = new InputStreamReader(gzis)
		) {
			return GSON.fromJson(reader, new TypeToken<List<Movie>>() {}.getType());
		} catch (IOException e) {
			throw new RuntimeException("Failed to load movie data", e);
		}
	}

	private static List<Credit> loadCredits() {
		try (
			var mongoClient = MongoClients.create(MONGO_URI)
		) {
			var creditsCollection = mongoClient.getDatabase("moviesDB").getCollection("credits");
			return StreamSupport.stream(creditsCollection.find().batchSize(5_000).map(Credit::new).spliterator(), false).toList();
		}
	}

	public record Movie(
		String id,
		String originalTitle,
		String overview,
		String releaseDate,
		String tagline,
		String title,
		String voteAverage
	) {
		public String toString() { return GSON.toJson(this).toString(); }
	}

	public static class Credit {
		String id;
		List<String> crew;
		List<String> cast;
		transient List<String> crewRole;

		private static final Pattern ROLE = Pattern.compile("\\((.*)\\)");

		public Credit(Document data) {
			this.id = data.getString("id");
			this.crew = data.getList("crew", String.class);
			this.cast = data.getList("cast", String.class);
			this.crewRole = data.getList("crew", String.class).stream().map(Credit::getRole).toList();
		}

		private static String getRole(String nameAndRole) {
			var matcher = ROLE.matcher(nameAndRole);
			matcher.find();
			return matcher.group(1);
		}
	}
	public record MovieWithCredits(Movie movie, List<Credit> credits) { }

	public enum CrewRole {
		Director, Writer, Screenplay, Editor, Animation, Other;

		public static final Map<String, CrewRole> ROLES_MAP =
			Arrays.stream(CrewRole.class.getEnumConstants()).collect(Collectors.toMap(CrewRole::toString, Function.identity()));

		public static CrewRole parseRole(String inputRole) {
			try {
				return CrewRole.valueOf(inputRole);
			} catch (IllegalArgumentException e) {
				LOG.trace("Unknown role", e);
				return CrewRole.Other;
			}
		}
	}
	public record StatsResult(int matchedMovies, Map<CrewRole, Long> crewCount) { }

	private static <T> Supplier<T> cache(Supplier<T> method) { return Suppliers.memoize(method); }
}
