import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

// cannot use the @cognizone one yet, as transloco moved from @ngneat to @jsverse
@Injectable({
  providedIn: 'root',
})
export class I18nService {
  private transloco = inject(TranslocoService);
  private storageKey = 'appLang';

  setActiveLang(lang: string): void {
    localStorage.setItem(this.storageKey, lang);
    this.transloco.setActiveLang(lang);
  }

  init(): void {
    const localLang = localStorage.getItem(this.storageKey);
    if (localLang) {
      this.transloco.setActiveLang(localLang);
    }
  }
}
