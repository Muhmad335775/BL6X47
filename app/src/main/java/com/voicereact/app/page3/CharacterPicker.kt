package com.voicereact.app.page3

/**
 * Guarantees fair coverage of all 15 characters: shuffles a fresh bag of 1..15 and hands
 * them out one at a time, only reshuffling once the bag is empty. This makes it
 * impossible for any character to be skipped across a full cycle of 15 recordings,
 * unlike plain Random.nextInt(1,16) which can (rarely but really) repeat the same
 * few numbers many times in a row by chance.
 */
object CharacterPicker {

    private var bag: MutableList<Int> = mutableListOf()

    @Synchronized
    fun next(): Int {
        if (bag.isEmpty()) {
            bag = (1..15).toMutableList()
            bag.shuffle()
        }
        return bag.removeAt(bag.size - 1)
    }
}
