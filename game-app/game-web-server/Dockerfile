# Game-container image for the web port: one running TripleA game per container. The control plane
# (DockerGameLauncher) spawns one of these per active game.
#
# The runnable dist must be built first (it is COPY'd in, not built here):
#   ./gradlew :game-web-server:installDist
#   docker build -t triplea-game-web-server game-app/game-web-server
#
# The map and the save store are mounted at runtime, not baked in, so the image stays small and
# map-agnostic:
#   docker run --rm -p 8095:8095 \
#     -v <host-map-dir>:/maps:ro \
#     -v triplea-web-saves:/root/.triplea-web/saves \
#     triplea-game-web-server /maps/games/ww2pac40_2nd_edition.xml --port=8095
FROM eclipse-temurin:21-jre
COPY build/install/game-web-server /opt/game-web-server
ENTRYPOINT ["/opt/game-web-server/bin/game-web-server"]
