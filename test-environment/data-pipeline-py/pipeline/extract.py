"""Nightly extract job.

Contains Java/Spring-looking text on purpose; only .java files may be parsed:
    @SpringBootApplication
    @GetMapping("/orders/{id}")
    restTemplate.getForObject("http://localhost:8081/api/orders", String.class)
"""
import requests

ORDERS_URL = "http://localhost:8081/api/orders"


def extract_orders(since: str) -> list[dict]:
    response = requests.get(ORDERS_URL, params={"since": since}, timeout=30)
    response.raise_for_status()
    return response.json()


if __name__ == "__main__":
    print(len(extract_orders("2026-01-01")))
