/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.utils

// I'd define this in MainActivity but it's not kotlin so it can't have a default implementation.
interface OnUrlEnteredListener {
    fun onUrlEntered(userInput: String) {}
}
