repl:
	mvn -Prepl compile exec:exec -DskipTests

demo:
	mvn -Prepl compile exec:exec -Dcloffle.args="--demo" -DskipTests

run:
	@test -n "$(FILE)" || (echo "Usage: make run FILE=path/to/script.clj" && exit 1)
	mvn -Prepl compile exec:exec -Dcloffle.args="$(FILE)" -DskipTests
