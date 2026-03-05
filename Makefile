repl:
	mvn -Prepl compile exec:java -Dexec.mainClass=net.javacrumbs.cloffle.CloffleREPL -DskipTests

demo:
	mvn -Prepl compile exec:java -Dexec.mainClass=net.javacrumbs.cloffle.CloffleREPL -Dexec.args="--demo" -DskipTests
