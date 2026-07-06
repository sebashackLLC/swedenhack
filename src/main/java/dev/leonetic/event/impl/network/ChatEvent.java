package dev.leonetic.event.impl.network;

import dev.leonetic.event.Event;

public class ChatEvent extends Event {
    private String content;

    public ChatEvent(String content) {
        this.content = content;
    }

    public String getMessage() {
        return content;
    }

    public void setMessage(String content) {
        this.content = content;
    }
}
