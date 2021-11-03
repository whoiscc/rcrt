import string
import random
import time


def generate_id(meta):
    while True:
        identifier = ''.join(random.choices(
            string.ascii_letters + string.digits,
            k=6
        ))
        if identifier not in meta:
            return identifier


def past_day(day_count):
    return time.time() - day_count * 86400
