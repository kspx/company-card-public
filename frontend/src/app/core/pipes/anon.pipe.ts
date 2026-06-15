import { Pipe, PipeTransform } from '@angular/core';
import { PseudonymService } from '@app/core/services/pseudonym.service';

@Pipe({ name: 'anon', standalone: true })
export class AnonPipe implements PipeTransform {
    constructor(private pseudo: PseudonymService) {}

    transform(uri: string | null | undefined, fallback: string): string {
        return this.pseudo.label(uri, fallback);
    }
}
