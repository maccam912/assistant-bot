package com.assistantbot.think;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.List;
import java.util.Map;

final class ThinkFixtures {
    static TypesafeConfig config() { return TypesafeConfig.from(Map.of("TYPESAFE_API_KEY", "test-key")::get); }
    static ThinkProtocol.Request request() {
        JsonObject state = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("text", "protect me bot!");
        messages.add(message);
        state.add("new_owner_messages", messages);
        return ThinkProtocol.request(state, "jev-latest", List.of("zombie"));
    }

    static JsonObject response(ThinkProtocol.Request request, String goal, String action, String target) {
        JsonObject answers = new JsonObject();
        request.body().getAsJsonObject("questions").entrySet().forEach(entry -> {
            JsonObject question = entry.getValue().getAsJsonObject();
            JsonObject answer = new JsonObject();
            answer.addProperty("type", question.get("type").getAsString());
            if (question.get("type").getAsString().equals("noul")) {
                answer.addProperty("noul", 0.95);
            } else {
                String selected = entry.getKey().equals("goal") ? goal : entry.getKey().equals("target") ? target : action;
                var options = question.getAsJsonObject("criteria");
                if (!options.has(selected)) selected = options.has("WAIT") ? "WAIT" : "NONE";
                answer.addProperty("choice", selected);
                answer.addProperty("confidence", 0.9);
                JsonObject probabilities = new JsonObject();
                for (String option : options.keySet()) probabilities.addProperty(option, option.equals(selected) ? 1.0 : 0.0);
                answer.add("probabilities", probabilities);
            }
            answers.add(entry.getKey(), answer);
        });
        JsonObject result = new JsonObject();
        result.add("answers", answers);
        return result;
    }

    static void choose(JsonObject response, String question, String value, double confidence) {
        JsonObject answer = response.getAsJsonObject("answers").getAsJsonObject(question);
        answer.addProperty("choice", value);
        answer.addProperty("confidence", confidence);
        JsonObject probabilities = answer.getAsJsonObject("probabilities");
        for (String option : probabilities.keySet()) probabilities.addProperty(option, option.equals(value) ? 1.0 : 0.0);
    }

    static void chooseWork(JsonObject response, ThinkProtocol.Request request, String branch, String id, double confidence) {
        if (id.equals("NONE")) { choose(response, "work_" + branch, "NONE", confidence); return; }
        String kind = request.work().get(id).kind();
        choose(response, "work_" + branch, kind, confidence);
        choose(response, "candidate_" + kind, id, confidence);
    }

    static ThinkProtocol.Evaluation protect() {
        var request = request();
        return ThinkProtocol.parse(response(request, "PROTECT", "ATTACK", "zombie"), request);
    }
}
