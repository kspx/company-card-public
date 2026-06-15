import { signal, WritableSignal } from '@angular/core';
import { Observable, takeUntil } from 'rxjs';

export function obsToSignal<T>(initialValue: T, obs$: Observable<T>, destroyed$: Observable<unknown>): WritableSignal<T> {
  const val = signal(initialValue);
  obs$.pipe(takeUntil(destroyed$)).subscribe(v => val.set(v));
  return val;
}
