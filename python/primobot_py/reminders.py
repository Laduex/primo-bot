from __future__ import annotations

from dataclasses import dataclass
from datetime import time


@dataclass(frozen=True)
class ReminderThread:
    thread_id: str
    name: str


class OrdersReminderMessageBuilder:
    def resolve_greeting(self, local_time: time) -> str:
        hour = local_time.hour
        if 5 <= hour <= 11:
            return "Good Morning"
        if 12 <= hour <= 17:
            return "Good Afternoon"
        return "Good Evening"

    def build_reminder_message(
        self,
        mention_role_id: str,
        greeting: str,
        forum_name: str,
        guild_id: str,
        open_threads: list[ReminderThread],
        signature: str | None,
        tone: str,
    ) -> str:
        content = (
            f"<@&{mention_role_id}> {greeting}, team! "
            f"Here are the orders still open in {forum_name}:\n\n"
        )

        for thread in open_threads:
            content += (
                f"- [{self._escape_brackets(thread.name)}]"
                f"(https://discord.com/channels/{guild_id}/{thread.thread_id})\n"
            )

        if tone.lower() == "casual":
            content += (
                "\nIf any of these are already done, please run `/completed` "
                "inside the order post so I can keep this list updated."
            )
        else:
            content += (
                "\nPlease run `/completed` inside each finished order post so this reminder "
                "stays accurate."
            )

        trimmed_signature = "" if signature is None else signature.strip()
        if trimmed_signature:
            content += f"\n\n{trimmed_signature}"

        return content

    @staticmethod
    def _escape_brackets(value: str | None) -> str:
        if value is None or not value.strip():
            return "Untitled Order"
        return value.replace("[", "\\[").replace("]", "\\]")
