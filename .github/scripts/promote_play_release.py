"""Copies the current release of one Play track to another track.

Used by .github/workflows/promote-play.yml, e.g. to move the build on the
internal track to closed testing without uploading it again (Play refuses a
versionCode it has already seen). Reads the service-account key from
PLAY_SERVICE_ACCOUNT_JSON.
"""

import json
import os
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"


def main(package, source, target):
    creds = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    session = AuthorizedSession(creds)

    def call(method, path, **kwargs):
        response = session.request(method, f"{API}/{package}{path}", **kwargs)
        if not response.ok:
            sys.exit(f"{method} {path} failed: {response.status_code} {response.text}")
        return response.json()

    edit = call("POST", "/edits")["id"]
    tracks = [t["track"] for t in call("GET", f"/edits/{edit}/tracks").get("tracks", [])]
    print("Tracks on this listing:", ", ".join(tracks))
    if target not in tracks:
        sys.exit(f"Track '{target}' not found; use one of the names above.")

    releases = [
        r for r in call("GET", f"/edits/{edit}/tracks/{source}").get("releases", [])
        if r.get("status") == "completed" and r.get("versionCodes")
    ]
    if not releases:
        sys.exit(f"No completed release on '{source}' to promote.")
    release = max(releases, key=lambda r: max(int(c) for c in r["versionCodes"]))
    promoted = {"versionCodes": release["versionCodes"], "status": "completed"}
    for key in ("name", "releaseNotes"):
        if key in release:
            promoted[key] = release[key]

    call("PUT", f"/edits/{edit}/tracks/{target}",
         json={"track": target, "releases": [promoted]})
    call("POST", f"/edits/{edit}:commit")
    print(f"Promoted {release.get('name', '')} (versionCodes "
          f"{', '.join(release['versionCodes'])}) from '{source}' to '{target}'.")


if __name__ == "__main__":
    main(*sys.argv[1:4])
