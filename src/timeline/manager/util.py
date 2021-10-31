import string
import random


def generate_id(meta):
    while True:
        identifier = ''.join(random.choices(
            string.ascii_letters + string.digits,
            k=6
        ))
        if identifier not in meta:
            return identifier
