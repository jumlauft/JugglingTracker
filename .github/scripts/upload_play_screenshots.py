"""Replaces the phone screenshots of the Play store listing.

Used by .github/workflows/play-screenshots.yml. Uploads every PNG in the given
folder, in file-name order, as the phone screenshots of each language the
listing has, replacing the ones there. Reads the service-account key from
PLAY_SERVICE_ACCOUNT_JSON.
"""

import json
import os
import pathlib
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"


def main(package, folder):
    shots = sorted(pathlib.Path(folder).glob("*.png"))
    if not shots:
        sys.exit(f"No PNG files in {folder}.")
    creds = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    session = AuthorizedSession(creds)

    def call(method, url, **kwargs):
        response = session.request(method, url, **kwargs)
        if not response.ok:
            sys.exit(f"{method} {url} failed: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    edit = call("POST", f"{API}/{package}/edits")["id"]
    languages = [l["language"] for l in call("GET", f"{API}/{package}/edits/{edit}/listings").get("listings", [])]
    if not languages:
        sys.exit("The listing has no languages yet.")
    for language in languages:
        images = f"{package}/edits/{edit}/listings/{language}/phoneScreenshots"
        call("DELETE", f"{API}/{images}")
        for shot in shots:
            call("POST", f"{UPLOAD_API}/{images}?uploadType=media",
                 data=shot.read_bytes(), headers={"Content-Type": "image/png"})
        print(f"{language}: {', '.join(s.name for s in shots)}")
    call("POST", f"{API}/{package}/edits/{edit}:commit")
    print("Committed. Google may review the listing change before it shows.")


if __name__ == "__main__":
    main(*sys.argv[1:3])
