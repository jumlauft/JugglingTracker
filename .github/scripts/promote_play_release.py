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


def main(package, source, target, status="completed"):
    creds = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    session = AuthorizedSession(creds)

    def call(method, path, **kwargs):
        response = session.request(method, f"{API}/{package}{path}", **kwargs)
        if not response.ok:
            raise PlayError(f"{method} {path} failed: {response.status_code} {response.text}")
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
    promoted = {"versionCodes": release["versionCodes"], "status": status}
    for key in ("name", "releaseNotes"):
        if key in release:
            promoted[key] = release[key]

    call("PUT", f"/edits/{edit}/tracks/{target}",
         json={"track": target, "releases": [promoted]})
    call("POST", f"/edits/{edit}:commit")
    print(f"Promoted {release.get('name', '')} (versionCodes "
          f"{', '.join(release['versionCodes'])}) from '{source}' to '{target}' "
          f"as {status}.")


class PlayError(Exception):
    pass


if __name__ == "__main__":
    package, source, target = sys.argv[1:4]
    try:
        main(package, source, target)
    except PlayError as error:
        # Until Google has approved the app's first release, the API only
        # accepts draft releases outside the internal track. Create a draft
        # instead, to be sent for review from the Play Console.
        if "draft app" not in str(error):
            sys.exit(str(error))
        print("The app has not been published yet, so the release is created "
              "as a draft. Open the track in the Play Console and send it for review.")
        try:
            main(package, source, target, status="draft")
        except PlayError as retry_error:
            sys.exit(str(retry_error))
