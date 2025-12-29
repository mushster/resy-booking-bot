#!/usr/bin/env python3
"""
Resy Bot Setup Utility
- Login to get auth token
- Search for restaurants and get venue IDs
- Update config file automatically
"""

import urllib.request
import urllib.parse
import json
import re
import sys
import getpass
from pathlib import Path

API_KEY = "VbWk7s3L4KiK5fzlO7JD3Q5EYolJI7n5"
CONFIG_PATH = Path(__file__).parent / "src/main/resources/resyConfig.conf"


def get_input(prompt: str) -> str:
    """Get input and strip carriage returns."""
    return input(prompt).replace('\r', '').strip()


HEADERS = {
    "Authorization": f'ResyAPI api_key="{API_KEY}"',
    "Origin": "https://resy.com",
    "Referer": "https://resy.com/",
}


def login(email: str, password: str) -> dict:
    """Login to Resy and return auth token + user info."""
    url = "https://api.resy.com/3/auth/password"
    data = urllib.parse.urlencode({"email": email, "password": password}).encode()

    req = urllib.request.Request(url, data=data, headers={
        **HEADERS,
        "Content-Type": "application/x-www-form-urlencoded",
    })

    try:
        resp = urllib.request.urlopen(req)
        result = json.loads(resp.read())
        return {
            "success": True,
            "auth_token": result.get("token"),
            "first_name": result.get("first_name"),
            "last_name": result.get("last_name"),
            "payment_methods": result.get("payment_method_id"),
        }
    except urllib.error.HTTPError as e:
        error = json.loads(e.read())
        return {"success": False, "error": error.get("message", "Login failed")}


def search_venues(query: str, lat: float = 40.7128, lon: float = -74.0060) -> list:
    """Search for restaurants by name."""
    url = "https://api.resy.com/3/venuesearch/search"
    data = json.dumps({
        "query": query,
        "geo": {"latitude": lat, "longitude": lon}
    }).encode()

    req = urllib.request.Request(url, data=data, headers={
        **HEADERS,
        "Content-Type": "application/json",
    })

    resp = urllib.request.urlopen(req)
    result = json.loads(resp.read())

    venues = []
    for hit in result.get("search", {}).get("hits", [])[:10]:
        venues.append({
            "name": hit.get("name"),
            "venue_id": hit.get("id", {}).get("resy"),
            "neighborhood": hit.get("neighborhood"),
            "region": hit.get("region"),
            "cuisine": ", ".join(hit.get("cuisine", [])),
        })
    return venues


def get_available_slots(venue_id: int, date: str, party_size: int = 2) -> list:
    """Get available reservation slots for a venue on a date."""
    url = f"https://api.resy.com/4/find?lat=0&long=0&day={date}&party_size={party_size}&venue_id={venue_id}"

    req = urllib.request.Request(url, headers=HEADERS)

    try:
        resp = urllib.request.urlopen(req)
        result = json.loads(resp.read())

        slots = []
        venues = result.get("results", {}).get("venues", [])
        if not venues:
            return []

        for slot in venues[0].get("slots", []):
            time = slot.get("date", {}).get("start", "")
            if " " in time:
                time = time.split(" ")[1]

            table_type = slot.get("config", {}).get("type", "")
            slots.append({
                "time": time,
                "table_type": table_type,
            })

        return slots
    except urllib.error.HTTPError as e:
        print(f"Error: {e.code} - {e.read().decode()[:200]}")
        return []


def update_config_auth(auth_token: str):
    """Update the config file with new auth token."""
    content = CONFIG_PATH.read_text()

    content = re.sub(
        r'resyKeys\.auth-token=.*',
        f'resyKeys.auth-token={auth_token}',
        content
    )

    CONFIG_PATH.write_text(content)
    print(f"Updated auth token in {CONFIG_PATH}")


def interactive_login():
    """Interactive login flow."""
    print("\n=== Resy Login ===")
    email = get_input("Email: ")
    password = getpass.getpass("Password: ").replace('\r', '').strip()

    print("\nLogging in...")
    result = login(email, password)

    if result["success"]:
        print(f"Welcome, {result['first_name']} {result['last_name']}!")
        print(f"Auth token obtained (expires in ~1 year)")

        update = get_input("\nUpdate config with new auth token? [Y/n]: ").lower()
        if update != 'n':
            update_config_auth(result["auth_token"])
        else:
            print(f"\nYour auth token:\n{result['auth_token']}")
    else:
        print(f"Login failed: {result['error']}")
        sys.exit(1)


def interactive_search():
    """Interactive venue search flow."""
    print("\n=== Venue Search ===")
    query = get_input("Restaurant name: ")

    if not query:
        print("No query provided")
        return

    print(f"\nSearching for '{query}'...")
    venues = search_venues(query)

    if not venues:
        print("No venues found")
        return

    print(f"\nFound {len(venues)} results:\n")
    for i, v in enumerate(venues, 1):
        print(f"  {i}. {v['name']}")
        print(f"     Venue ID: {v['venue_id']}")
        print(f"     Location: {v['neighborhood']}, {v['region']}")
        if v['cuisine']:
            print(f"     Cuisine: {v['cuisine']}")
        print()


def interactive_slots():
    """Interactive slot lookup flow."""
    print("\n=== Available Slots ===")
    venue_id = get_input("Venue ID: ")
    date = get_input("Date (YYYY-MM-DD): ")
    party_size = get_input("Party size [2]: ") or "2"

    if not venue_id or not date:
        print("Venue ID and date are required")
        return

    print(f"\nFetching available slots...")
    slots = get_available_slots(int(venue_id), date, int(party_size))

    if not slots:
        print("No slots available (or restaurant not on Resy for that date)")
        return

    by_time = {}
    for slot in slots:
        time = slot["time"]
        if time not in by_time:
            by_time[time] = []
        by_time[time].append(slot["table_type"])

    print(f"\nFound {len(slots)} slots:\n")
    for time in sorted(by_time.keys()):
        table_types = by_time[time]
        print(f"  {time}")
        for tt in sorted(set(table_types)):
            print(f"    - {tt}")


def main():
    print("=" * 50)
    print("  Resy Booking Bot Setup Utility")
    print("=" * 50)

    while True:
        print("\nOptions:")
        print("  1. Login (get auth token)")
        print("  2. Search venues (get venue ID)")
        print("  3. Check available slots & table types")
        print("  4. Exit")

        choice = get_input("\nSelect [1-4]: ")

        if choice == "1":
            interactive_login()
        elif choice == "2":
            interactive_search()
        elif choice == "3":
            interactive_slots()
        elif choice == "4":
            print("\nGoodbye!")
            break
        else:
            print("Invalid choice")


if __name__ == "__main__":
    main()
