package com.assistantbot.think;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: uses a real API key, never runs as part of an ordinary build. */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_LIVE_TEST", matches = "1")
class TypesafeLiveTest {
    @ParameterizedTest
    @ValueSource(strings = {"collect", "hut_empty", "hut_logs", "hut_planks", "pickup",
            "collect_pickup", "collect_done", "resume", "dig", "no_resources"})
    void survivalWorkDecisions(String scenario) throws Exception {
        var config = TypesafeConfig.load();
        Files.createDirectories(Path.of("build/typesafe-live"));
        int repeats = Math.min(10, Math.max(1, Integer.parseInt(System.getenv().getOrDefault("TYPESAFE_LIVE_REPEATS", "1"))));
        for (int attempt = 1; attempt <= repeats; attempt++) {
            var request = scenario(scenario, config.model());
            Files.writeString(Path.of("build/typesafe-live/" + scenario + "-request.json"), request.body().toString());
            var evaluation = new TypesafeClient(config).evaluate(request).get();
            var decision = ThinkProtocol.decide(scenario.equals("resume") ? ThinkProtocol.Goal.PROJECT : scenario.equals("collect_done")
                    ? ThinkProtocol.Goal.COLLECT_WOOD : ThinkProtocol.Goal.FOLLOW, evaluation, config);
            String report = scenario + " attempt " + attempt + ": " + decision + " goal=" + evaluation.goal()
                    + " action=" + evaluation.actions().get(decision.goal());
            System.out.println(report);
            Files.writeString(Path.of("build/typesafe-live/" + scenario + "-result-" + attempt + ".txt"), report);
            assertEquals(scenario.startsWith("collect") ? ThinkProtocol.Goal.COLLECT_WOOD : ThinkProtocol.Goal.PROJECT, decision.goal());
            if (scenario.equals("collect_done") || scenario.equals("no_resources")) {
                assertEquals(scenario.equals("collect_done") ? ThinkProtocol.Action.COMPLETE : ThinkProtocol.Action.ASK_HELP,
                        decision.action(), report);
                continue;
            }
            assertEquals(ThinkProtocol.Action.WORK, decision.action(), report);
            assertTrue(switch (scenario) {
                case "collect", "hut_empty", "resume" -> decision.work().kind().equals("chop");
                case "hut_logs" -> decision.work().kind().equals("craft");
                case "hut_planks" -> decision.work().kind().equals("place");
                case "pickup", "collect_pickup" -> decision.work().kind().equals("pickup_wood");
                case "dig" -> decision.work().kind().equals("dig");
                default -> false;
            }, report);
        }
    }

    static ThinkProtocol.Request scenario(String scenario, String model) {
        JsonObject state = JsonParser.parseString("""
                {"bot":{"position":{"x":0.5,"y":64,"z":0.5},"health":20,"food":20,"held_item":"minecraft:air"},
                 "owner":{"position":{"x":-2,"y":64,"z":0},"distance_from_bot":2.5},
                 "memory":{"goal":"FOLLOW","goal_instructions":[],"last_action":"WAIT","last_outcome":"starting","scan_radius":20},
                 "hostiles":[],"recent_work":[],"temporarily_blocked_work":[],"inventory":[],"placeable_materials":{}}
                """).getAsJsonObject();
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("text", scenario.startsWith("collect") ? "collect 16 logs" : scenario.equals("dig")
                ? "Dig out the grass block at 1, 63, 1" : "Please build me a little survival hut");
        messages.add(message);
        state.add("new_owner_messages", messages);
        state.add("recent_owner_messages", messages.deepCopy());
        if (scenario.equals("hut_logs") || scenario.equals("hut_planks") || scenario.equals("collect_done")) {
            String item = !scenario.equals("hut_planks") ? "minecraft:oak_log" : "minecraft:oak_planks";
            JsonObject stack = new JsonObject();
            stack.addProperty("item", item); stack.addProperty("count", scenario.equals("collect_done") ? 16 : 32);
            state.getAsJsonArray("inventory").add(stack);
            state.getAsJsonObject("placeable_materials").addProperty(item, stack.get("count").getAsInt());
        }
        JsonArray terrain = new JsonArray();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 63; y <= 68; y++) {
            JsonArray cell = new JsonArray();
            cell.add(x); cell.add(y); cell.add(z);
            cell.add(y == 63 ? "minecraft:grass_block" : x == 3 && z == 0 && y < 67 ? "minecraft:oak_log"
                    : x == 3 && z == 0 && y == 67 ? "minecraft:oak_leaves" : "minecraft:air");
            terrain.add(cell);
        }
        state.add("terrain", terrain);
        Map<String, ThinkProtocol.Work> work = new LinkedHashMap<>();
        work.put("tree", new ThinkProtocol.Work("chop", 3, 64, 0, "minecraft:oak_log"));
        work.put("walk", new ThinkProtocol.Work("move", 2, 64, 0, ""));
        work.put("dirt", new ThinkProtocol.Work("dig", 1, 63, 1, "minecraft:grass_block"));
        for (int x = 1; x <= 4; x++) for (int z = 1; z <= 4; z++)
            work.put("place_" + x + "_" + z, new ThinkProtocol.Work("place", x, 64, z, "selected material"));
        if (scenario.equals("hut_logs")) work.put("craft", new ThinkProtocol.Work("craft", 0, 0, 0, "minecraft:oak_log"));
        if (scenario.equals("pickup") || scenario.equals("collect_pickup")) work.put("drop", new ThinkProtocol.Work("pickup_wood", 1, 64, 0, "dropped-log"));
        if (scenario.equals("no_resources")) work.clear();
        if (scenario.equals("collect_done")) {
            state.add("new_owner_messages", new JsonArray());
            state.getAsJsonObject("memory").addProperty("goal", "COLLECT_WOOD");
            state.getAsJsonObject("memory").add("goal_instructions", messages.deepCopy());
            state.getAsJsonObject("memory").addProperty("last_outcome", "picked up the last logs; inventory now has 16 logs");
        }
        if (scenario.equals("resume")) {
            state.add("new_owner_messages", new JsonArray());
            var memory = state.getAsJsonObject("memory");
            memory.addProperty("goal", "PROJECT");
            memory.add("goal_instructions", messages.deepCopy());
            memory.add("goal_origin", state.getAsJsonObject("bot").get("position").deepCopy());
            memory.addProperty("last_outcome", "placed minecraft:oak_planks at 1, 64, 1; now out of planks");
            JsonArray edits = new JsonArray();
            edits.add(JsonParser.parseString("{\"x\":1,\"y\":64,\"z\":1,\"expected_block\":\"minecraft:oak_planks\",\"current_block\":\"minecraft:oak_planks\"}"));
            state.add("project_edits", edits);
            for (var cell : terrain) {
                var c = cell.getAsJsonArray();
                if (c.get(0).getAsInt() == 1 && c.get(1).getAsInt() == 64 && c.get(2).getAsInt() == 1)
                    c.set(3, new com.google.gson.JsonPrimitive("minecraft:oak_planks"));
            }
            work.remove("place_1_1");
        }
        JsonObject candidates = new JsonObject();
        work.forEach((id, candidate) -> candidates.addProperty(id, candidate.toString()
                + (id.equals("drop") ? " item=minecraft:oak_log count=4" : "")
                + (id.equals("craft") ? " consumes 1 log, produces 4 minecraft:oak_planks" : "")));
        state.add("work_candidates", candidates);
        return ThinkProtocol.request(state, model, List.of(), work);
    }
}
